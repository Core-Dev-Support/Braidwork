package com.naua_braidwork.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Пересылка уведомлений с телефона на другие устройства семьи.
 *
 * Механизм тот же, что в Phone Link: уведомление читается штатным API системы
 * и отправляется по локальной сети. Содержимое чужих переписок не вытаскивается
 * из баз приложений — доступ к ним ОС не даёт, и обходить это нельзя.
 *
 * Сервис включается пользователем вручную в настройках, автоматически
 * включить его нельзя.
 */
class NotificationRelayService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Заглушка: пересылка появится вместе с транспортом.
        // Само событие здесь намеренно проглатывается, а не пишется в лог:
        // заголовок чужого сообщения не должен попадать в лог устройства.
    }

    override fun onListenerConnected() {
        // Подключение подтверждено системой. Дальше ядро само запросит
        // доступ к уведомлениям через выданное пользователем разрешение.
    }
}