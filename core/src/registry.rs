//! Реестр объектов ядра, видимых клиенту как хэндлы.
//!
//! Клиент не должен получать указатели: они не переживают перемещение объекта и
//! ломают 32-битный Android. Наружу отдаётся `u64` — индекс слота плюс счётчик
//! поколений.
//!
//! Почему не `OnceLock<RwLock<HashMap<u64, T>>>`: глобальная карта с одним
//! замком становится бутылочным горлышком, а после первой же паники RwLock
//! отравляется и остаётся отравленным навсегда — то есть после любой ошибки
//! ядро перестаёт работать до перезапуска процесса клиента (D-009).
//!
//! Здесь `Mutex<Vec<Slot<T>>>` плюс стек свободных индексов. Слоты
//! переиспользуются: приложение живёт долго, сессии создаются и освобождаются
//! без конца, и вектор, который только растёт, со временем съел бы память.
//!
//! Поколение увеличивается при освобождении слота, и именно ради этого оно
//! нужно: без него сохранённый клиентом хэндл после переиспользования слота
//! указал бы на чужой объект. Это поведение называют ABA, и проверяет его тест
//! `устаревший_хэндл_не_указывает_на_новый_объект`.

use std::sync::Mutex;

use crate::FlHandle;

/// Хэндл не ссылается ни на один объект.
pub(crate) const HANDLE_INVALID: FlHandle = FlHandle(0);

/// Начальное поколение. Ноль зарезервирован под «слот не выдавался».
pub(crate) const GENERATION_START: u64 = 1;

/// Собирает хэндл из индекса слота и поколения.
///
/// Индекс берётся у самой таблицы, а не из отдельного счётчика: счётчик
/// разошёлся бы с таблицей при первом же переиспользовании слота, и хэндл начал
/// бы указывать на чужой объект. Молчаливый источник таких ошибок опаснее явной
/// проверки.
///
/// Ноль не может получиться: поколение всегда не ноль, поэтому младшие 32 бита
/// ненулевые и весь хэндл тоже. Клиент использует `HANDLE_INVALID` как признак
/// «объекта нет», и выдача нуля означала бы, что несуществующий объект выглядит
/// как освобождённый.
pub(crate) fn make_handle(index: usize, generation: u64) -> FlHandle {
    debug_assert!(index <= u32::MAX as usize, "слот исчерпан");
    debug_assert!(
        generation > 0 && generation <= u32::MAX as u64,
        "поколение вне диапазона"
    );
    FlHandle(((index as u64) << 32) | (generation & 0xffff_ffff))
}

/// Разбирает хэндл на слот и поколение.
pub(crate) fn split_handle(handle: FlHandle) -> (usize, u64) {
    let raw = handle.0;
    ((raw >> 32) as usize, raw & 0xffff_ffff)
}

impl FlHandle {
    /// Хэндл не ссылается ни на один объект.
    pub fn is_valid(&self) -> bool {
        *self != HANDLE_INVALID
    }
}

/// Один слот: объект и его поколение.
///
/// Поколение принадлежит слоту, а не объекту: слот переживает много объектов,
/// и именно его рост делает старые хэндлы нерабочими.
struct Slot<T> {
    value: Option<T>,
    generation: u64,
}

struct Inner<T> {
    slots: Vec<Slot<T>>,
    /// Индексы освобождённых слотов. Стек, а не очередь: слот, которым
    /// владели последним, вероятнее всего освободится следующим, и порядок
    /// разбора от этого ничего не зависит.
    free: Vec<usize>,
}

/// Таблица объектов одного типа.
pub(crate) struct Registry<T> {
    inner: Mutex<Inner<T>>,
}

impl<T> Registry<T> {
    pub(crate) fn new() -> Self {
        Registry {
            inner: Mutex::new(Inner {
                slots: Vec::new(),
                free: Vec::new(),
            }),
        }
    }

    /// Запирает таблицу, переживая отравление.
    ///
    /// Отравление — это «поток упал, держав замок». Сами данные после этого
    /// целы, поэтому забирать их можно: иначе одна паника внутри ядра
    /// выключала бы все сессии до перезапуска приложения, и клиент не смог бы
    /// даже показать пользователю, что произошло.
    fn lock(&self) -> std::sync::MutexGuard<'_, Inner<T>> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Кладёт объект и выдаёт его хэндл.
    pub(crate) fn insert(&self, value: T) -> FlHandle {
        let mut inner = self.lock();

        let (index, generation) = match inner.free.pop() {
            Some(index) => {
                let slot = &mut inner.slots[index];
                slot.value = Some(value);
                (index, slot.generation)
            }
            None => {
                let index = inner.slots.len();
                let generation = GENERATION_START;
                inner.slots.push(Slot {
                    value: Some(value),
                    generation,
                });
                (index, generation)
            }
        };

        make_handle(index, generation)
    }

    /// Выполняет действие над объектом.
    ///
    /// `None` означает «объекта нет»: хэндл освобождён, устарел или никогда не
    /// выдавался. Отличать эти случаи клиенту не нужно — во всех трёх верное
    /// действие одно и то же.
    ///
    /// Замыкание выполняется под замком, поэтому внутри нельзя уйти в
    /// рекурсию по тому же реестру. Все операции с реестром короткие, и
    /// переинтерактивность здесь означала бы блокировку.
    pub(crate) fn with<R>(&self, handle: FlHandle, f: impl FnOnce(&mut T) -> R) -> Option<R> {
        if !handle.is_valid() {
            return None;
        }
        let (index, generation) = split_handle(handle);
        let mut inner = self.lock();

        let slot = inner.slots.get_mut(index)?;
        if slot.generation != generation {
            // Слот переиспользован после освобождения: хэндл устарел.
            return None;
        }
        let value = slot.value.as_mut()?;
        Some(f(value))
    }

    /// Убирает объект.
    ///
    /// Слот возвращается в свободные сразу, но поколение увеличивается, поэтому
    /// хэндл, который клиент успел сохранить, перестаёт работать.
    pub(crate) fn remove(&self, handle: FlHandle) -> bool {
        if !handle.is_valid() {
            return false;
        }
        let (index, generation) = split_handle(handle);
        let mut inner = self.lock();

        let Some(slot) = inner.slots.get_mut(index) else {
            return false;
        };
        if slot.generation != generation {
            return false;
        }
        if slot.value.take().is_none() {
            return false;
        }

        slot.generation = slot.generation.wrapping_add(1).max(GENERATION_START);
        inner.free.push(index);
        true
    }

    /// Сколько объектов живёт сейчас. Только для проверок и диагностики.
    #[cfg(test)]
    pub(crate) fn live(&self) -> usize {
        self.lock()
            .slots
            .iter()
            .filter(|s| s.value.is_some())
            .count()
    }

    /// Сколько слотов занято когда-либо. Растёт только до пикового числа
    /// одновременных объектов.
    #[cfg(test)]
    pub(crate) fn slots_used(&self) -> usize {
        self.lock().slots.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn хэндлы_разбираются_на_слот_и_поколение() {
        let h = make_handle(3, GENERATION_START);
        let (slot, gen) = split_handle(h);
        assert_eq!(slot, 3);
        assert_eq!(gen, GENERATION_START);
        // Ноль зарезервирован под «объекта нет», выдавать его нельзя.
        assert_ne!(h, HANDLE_INVALID);
        assert!(h.is_valid());
        assert!(!HANDLE_INVALID.is_valid());
    }

    #[test]
    fn у_разных_объектов_разные_слоты() {
        let reg = Registry::<u32>::new();
        let a = reg.insert(1);
        let b = reg.insert(2);
        assert_ne!(split_handle(a).0, split_handle(b).0);
    }

    #[test]
    fn объект_достаётся_по_своему_хэндлу() {
        let reg = Registry::new();
        let h = reg.insert(42u32);

        assert_eq!(reg.with(h, |v| *v), Some(42));
        assert_eq!(reg.live(), 1);

        reg.with(h, |v| *v = 7);
        assert_eq!(reg.with(h, |v| *v), Some(7));
    }

    #[test]
    fn освобождённый_хэндл_перестаёт_работать() {
        let reg = Registry::new();
        let h = reg.insert(42u32);

        assert!(reg.remove(h));
        assert_eq!(reg.with(h, |v| *v), None);
        assert_eq!(reg.live(), 0);

        // Повторное освобождение ничего не делает и не паникует.
        assert!(!reg.remove(h));
    }

    /// Главная причина, по которой в хэндле есть поколение.
    ///
    /// Слот переиспользуется, индекс совпадает, а хэндл уже чужой.
    #[test]
    fn устаревший_хэндл_не_указывает_на_новый_объект() {
        let reg = Registry::new();
        let старый = reg.insert(1u32);
        reg.remove(старый);

        let новый = reg.insert(2u32);
        // Слот переиспользован, поэтому индекс совпадает.
        assert_eq!(split_handle(старый).0, split_handle(новый).0);
        // Поколение — нет.
        assert_ne!(split_handle(старый).1, split_handle(новый).1);

        assert_eq!(
            reg.with(старый, |v| *v),
            None,
            "старый хэндл не должен доставать новый объект"
        );
        assert_eq!(reg.with(новый, |v| *v), Some(2));
        assert!(
            !reg.remove(старый),
            "старым хэндлом нельзя освободить новый объект"
        );
    }

    /// Реестр не должен расти бесконечно: приложение живёт долго, сессии
    /// создаются и освобождаются без конца.
    #[test]
    fn слоты_переиспользуются_без_роста_таблицы() {
        let reg = Registry::new();

        let первый = reg.insert(1u32);
        reg.remove(первый);

        for i in 2..100u32 {
            let h = reg.insert(i);
            reg.remove(h);
        }

        assert_eq!(
            reg.slots_used(),
            1,
            "таблица должна остаться в размере пикового числа объектов"
        );
        assert_eq!(reg.live(), 0);
    }

    /// Много одновременных объектов: каждый должен доставаться по своему
    /// хэндлу, даже если часть слотов освобождена.
    #[test]
    fn одновременные_объекты_не_путаются() {
        let reg = Registry::new();
        let mut handles = Vec::new();
        for i in 0..16u32 {
            handles.push(reg.insert(i));
        }
        assert_eq!(reg.live(), 16);

        // Освобождаем через одну.
        for h in handles.iter().step_by(2) {
            assert!(reg.remove(*h));
        }

        for (i, h) in handles.iter().enumerate() {
            if i % 2 == 0 {
                assert_eq!(reg.with(*h, |v| *v), None, "освобождённый хэндл пуст");
            } else {
                assert_eq!(
                    reg.with(*h, |v| *v),
                    Some(i as u32),
                    "чужой объект не подставляется"
                );
            }
        }
    }

    #[test]
    fn неизвестный_и_нулевой_хэндлы_отвергаются() {
        let reg = Registry::<u32>::new();
        assert_eq!(reg.with(HANDLE_INVALID, |v| *v), None);
        // Хэндл на существующий по форме, но не выдававшийся слот.
        let чужой = make_handle(9999, GENERATION_START);
        assert_eq!(reg.with(чужой, |v| *v), None);
    }

    /// Реестр переживает панику внутри замыкания.
    ///
    /// Отравленный `Mutex` блокирует всё, пока его явно не разблокируют. Без
    /// этого одна ошибка внутри ядра выключала бы все сессии до перезапуска
    /// приложения, и клиент не смог бы даже показать пользователю, что случилось.
    #[test]
    fn реестр_переживает_отравление_замка() {
        let reg = Registry::new();
        let h = reg.insert(5u32);

        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            reg.with(h, |_| panic!("намеренно"));
        }));

        assert_eq!(
            reg.with(h, |v| *v),
            Some(5),
            "после паники реестр обязан остаться рабочим"
        );
    }
}
