/*
 * JNI-прокладка клиента Braidwork.
 *
 * Тонкий слой: переводит типы JNI в вызовы C ABI и ничего не решает.
 * Вся логика — в ядре.
 *
 * Имена методов в Kotlin объявлены как private external, поэтому JNI видит их
 * через FindClass по полному имени класса.
 *
 * Соглашение об именах: <Java-класс>_<метод>. Здесь это
 * BraidworkCore_nativeInit и так далее.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

/* Прототипы ядра. Дублируют include/braidwork_core.h намеренно: заголовок лежит
 * в репозитории ядра, а Android-сборка берёт его оттуда на конкретную ревизию.
 * Здесь достаточно превийма, чтобы компилятор проверял аргументы. */
extern int32_t fl_init(uint8_t *out_public, size_t *out_len);
extern int32_t fl_device_id(const uint8_t *signing_public, uint8_t *out_id, size_t *out_len);
extern int32_t fl_verify_signature(const uint8_t *signing_public,
                                   const uint8_t *tape, size_t tape_len,
                                   const uint8_t *signature);
extern uint32_t fl_abi_version(void);

/* Модуль ядра грузится в BraidworkCore.ensureLoaded(). Здесь только проверка,
 * что ABI тот, о котором клиент думает: иначе клиент читал бы неверную память. */
JNIEXPORT jint JNICALL
Java_com_naua_braidwork_app_BraidworkCore_nativeAbiVersion(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) fl_abi_version();
}

/*
 * Генерация ключей устройства.
 *
 * Ядро пишет в out_public ровно FL_PUBLIC_KEYS_LEN байт, но длину отдаёт
 * отдельно. Создаём массив по возвращённой длине, а не по константе, чтобы
 * рассинхрон заметить рассинхрон, а не читать лишнее.
 */
JNIEXPORT jint JNICALL
Java_com_naua_braidwork_app_BraidworkCore_nativeInit(JNIEnv *env, jclass clazz,
                                                     jbyteArray out_public, jintArray out_len) {
    (void) clazz;
    if (out_public == NULL || out_len == NULL) return 1; /* FL_ERR_INVALID_ARG */

    jsize out_len_len = (*env)->GetArrayLength(env, out_len);
    if (out_len_len < 1) return 1;

    size_t capacity = 128;
    uint8_t *buffer = (uint8_t *) malloc(capacity);
    if (buffer == NULL) return 2; /* FL_ERR_NO_MEMORY */

    size_t actual = 0;
    int32_t status = fl_init(buffer, &actual);

    if (status == 0 && actual > 0 && actual <= capacity) {
        jbyteArray result = (*env)->NewByteArray(env, (jsize) actual);
        if (result != NULL) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize) actual, (const jbyte *) buffer);
            (*env)->SetByteArrayRegion(env, out_public, 0, (jsize) actual, (const jbyte *) buffer);
            (*env)->SetIntArrayRegion(env, out_len, 0, 1, (const jint *) &actual);
        } else {
            status = 2;
        }
    }

    /* Ключи чувствительные: буфер затираем перед освобождением. */
    for (size_t i = 0; i < capacity; i++) buffer[i] = 0;
    free(buffer);
    return status;
}

/* device_id по публичному ключу подписи. */
JNIEXPORT jint JNICALL
Java_com_naua_braidwork_app_BraidworkCore_nativeDeviceId(JNIEnv *env, jclass clazz,
                                                        jbyteArray signing_public,
                                                        jbyteArray out_id,
                                                        jintArray out_len) {
    (void) clazz;
    if (signing_public == NULL || out_id == NULL || out_len == NULL) return 1;

    jbyte *key = (*env)->GetByteArrayElements(env, signing_public, NULL);
    if (key == NULL) return 2;

    uint8_t buffer[64];
    size_t actual = 0;
    int32_t status = fl_device_id((const uint8_t *) key, buffer, &actual);

    if (status == 0 && actual > 0 && actual <= sizeof(buffer)) {
        jsize id_len = (*env)->GetArrayLength(env, out_id);
        jsize len_len = (*env)->GetArrayLength(env, out_len);
        if (id_len >= (jsize) actual && len_len >= 1) {
            (*env)->SetByteArrayRegion(env, out_id, 0, (jsize) actual, (const jbyte *) buffer);
            (*env)->SetIntArrayRegion(env, out_len, 0, 1, (const jint *) &actual);
        } else {
            status = 1;
        }
    }

    (*env)->ReleaseByteArrayElements(env, signing_public, key, JNI_ABORT);
    return status;
}

/*
 * Проверка подписи рукопожатия.
 *
 * Вызывается на каждом установлении соединения, поэтому результат кешировать
 * нельзя: кеш здесь означал бы, что подделанное устройство пройдёт один раз.
 */
JNIEXPORT jint JNICALL
Java_com_naua_braidwork_app_BraidworkCore_nativeVerifySignature(JNIEnv *env, jclass clazz,
                                                                jbyteArray signing_public,
                                                                jbyteArray tape,
                                                                jbyteArray signature) {
    (void) clazz;
    if (signing_public == NULL || tape == NULL || signature == NULL) return 1;

    jsize tape_len = (*env)->GetArrayLength(env, tape);
    jbyte *tape_bytes = (*env)->GetByteArrayElements(env, tape, NULL);
    if (tape_bytes == NULL) return 2;

    jbyte *key = (*env)->GetByteArrayElements(env, signing_public, NULL);
    jbyte *sig = (*env)->GetByteArrayElements(env, signature, NULL);

    int32_t status;
    if (key == NULL || sig == NULL) {
        status = 2;
    } else {
        status = fl_verify_signature((const uint8_t *) key,
                                     (const uint8_t *) tape_bytes, (size_t) tape_len,
                                     (const uint8_t *) sig);
    }

    if (key != NULL) (*env)->ReleaseByteArrayElements(env, signing_public, key, JNI_ABORT);
    if (sig != NULL) (*env)->ReleaseByteArrayElements(env, signature, sig, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, tape, tape_bytes, JNI_ABORT);
    return status;
}