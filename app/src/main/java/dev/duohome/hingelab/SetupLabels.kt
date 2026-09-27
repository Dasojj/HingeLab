package dev.duohome.hingelab

import java.util.Locale

/** Samsung translations use several visually identical Wi-Fi hyphens. */
object SetupLabels {
    fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[\\s\\p{Z}\\p{Pd}−]+"), " ").trim()
    fun wireless(value: String) = normalize(value) in setOf(
        "отладка по wi fi", "беспроводная отладка", "wireless debugging")
    fun pairing(value: String) = normalize(value) in setOf(
        "подключить устройство с помощью кода", "pair device with pairing code",
        "сопряжение с помощью кода", "подключение устройства с помощью кода")
}
