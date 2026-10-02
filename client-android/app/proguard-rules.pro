# Правила для release-сборки. Минимальные: клиент — тонкая оболочка над ядром,
# и всё, что не используется, должно удаляться без вреда.

# Имена методов ядра вызываются из Kotlin по строке через System.loadLibrary.
# ProGuard не видит этих вызовов, поэтому сигнатуры сохраняются явно.
-keep class com.naua_braidwork.app.BraidworkCore { *; }

# Нативные методы JNI: имена связываются по шаблону Java_имя_метод.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Служба пересылки уведомлений вызывается системой по имени из манифеста.
-keep class com.naua_braidwork.app.NotificationRelayService { *; }
-keep class com.naua_braidwork.app.LinkService { *; }

# Отладочные сборки не сжимаются, поэтому правила на них не влияют.
-dontwarn kotlinx.**