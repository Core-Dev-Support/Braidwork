package com.naua_braidwork.app.ui.state

import androidx.compose.ui.graphics.vector.ImageVector
import com.naua_braidwork.app.ui.icons.IcwBell
import com.naua_braidwork.app.ui.icons.IcwClipboard
import com.naua_braidwork.app.ui.icons.IcwCpu
import com.naua_braidwork.app.ui.icons.IcwFolder
import com.naua_braidwork.app.ui.icons.IcwInfo
import com.naua_braidwork.app.ui.icons.IcwMirror
import com.naua_braidwork.app.ui.icons.IcwMoon
import com.naua_braidwork.app.ui.icons.IcwPower
import com.naua_braidwork.app.ui.icons.IcwShield
import com.naua_braidwork.app.ui.icons.IcwWifi

/** Участник семьи с ролью. */
data class FamilyMember(
    val letter: String,
    val name: String,
    val subtitle: String,
    val role: String,
)

/** Состояние раздела «Семья». */
data class FamilyState(
    val name: String,
    val participants: Int,
    val devices: Int,
    val avatars: List<String>,
    val members: List<FamilyMember>,
)

/** Строка настроек: заголовок, значение справа и иконка в плашке. */
data class SettingsRow(
    val id: String,
    val title: String,
    val value: String,
    val icon: ImageVector,
    val tone: Tone,
)

/** Группа настроек с необязательным заголовком. */
data class SettingsGroup(
    val title: String,
    val rows: List<SettingsRow>,
)