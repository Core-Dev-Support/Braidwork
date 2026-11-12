package com.naua_braidwork.app.ui.screens

import com.naua_braidwork.app.ui.components.tapNoRipple
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.icons.IcwChevron
import com.naua_braidwork.app.ui.icons.IcwGlobe
import com.naua_braidwork.app.ui.icons.IcwKey
import com.naua_braidwork.app.ui.icons.IcwPlus
import com.naua_braidwork.app.ui.icons.IcwQr
import com.naua_braidwork.app.ui.icons.IcwRefresh
import com.naua_braidwork.app.ui.state.FamilyState
import com.naua_braidwork.app.ui.state.FamilyMember
import com.naua_braidwork.app.ui.theme.LocalBwColors

/**
 * Семья по спецификации `design/spec/SPEC.txt`.
 *
 * Название семьи и состав участников пока не подставляются: пока нет ввода
 * устройств, любое имя было бы выдумкой на экране, который показывают людям.
 */
@Composable
fun FamilyScreen(
    members: FamilyState,
    onInvite: () -> Unit,
    onOpenRoute: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Семья",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Устройства связаны напрямую",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .tapNoRipple(onClick = onInvite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = IcwPlus,
                    contentDescription = "Пригласить",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (members.avatars.isEmpty()) {
                        Text(
                            text = "Пока нет участников",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        members.avatars.forEachIndexed { index, letter ->
                            Avatar(letter = letter, tone = index)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = members.name,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${members.participants} участников · " +
                        "${members.devices} устройств",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(18.dp))
                InviteButton(onClick = onInvite)
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            text = "Участники",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(10.dp))

        if (members.members.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Участников пока нет",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Устройство попадает в семью один раз: его вводит " +
                            "тот, кому вы доверяете. Само по себе оно не подключится.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            MemberList(members.members)
        }

        Spacer(Modifier.height(22.dp))
        Text(
            text = "Сеть семьи",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(10.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                FamilyNetRow(
                    icon = IcwRefresh,
                    iconBg = Color(0xFFE4F1FF),
                    iconFg = Color(0xFF1A6FDB),
                    title = "Адреса устройств",
                    subtitle = "Обновлены только что",
                    onClick = { onOpenRoute("network") },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                FamilyNetRow(
                    icon = IcwGlobe,
                    iconBg = Color(0xFFE8F6EE),
                    iconFg = Color(0xFF16804A),
                    title = "Прямые соединения",
                    subtitle = "Локальная сеть · Авто",
                    onClick = { onOpenRoute("network") },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                FamilyNetRow(
                    icon = IcwKey,
                    iconBg = Color(0xFFEFEAFB),
                    iconFg = Color(0xFF6038C9),
                    title = "Кто выдаёт ключи",
                    subtitle = "Доверенные участники семьи",
                    onClick = { onOpenRoute("security") },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FamilyNetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    iconFg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tapNoRipple(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconBg, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconFg,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = IcwChevron,
            contentDescription = null,
            tint = Color(0xFF8A8D97),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Круг с буквой. Тон задаёт цвет, как в макете. */
@Composable
private fun Avatar(letter: String, tone: Int, modifier: Modifier = Modifier) {
    val bw = LocalBwColors.current
    val (background, foreground) = when (tone % 4) {
        0 -> bw.blueSubtle to MaterialTheme.colorScheme.primary
        1 -> bw.greenSubtle to bw.online
        2 -> bw.orangeSubtle to bw.warning
        else -> bw.purpleSubtle to MaterialTheme.colorScheme.tertiary
    }

    Box(
        modifier = modifier
            .size(52.dp)
            .background(background, RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
    }
}

@Composable
private fun InviteButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Минимум: надпись кнопки переносится при крупном системном шрифте.
            .heightIn(min = 52.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .tapNoRipple(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = IcwQr, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = "Пригласить по QR", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Строки участников с ролью. */
@Composable
private fun MemberList(members: List<FamilyMember>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            members.forEachIndexed { index, member ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Минимум: в строке участника имя и роль, оба
                        // переносятся при крупном системном шрифте.
                        .heightIn(min = 72.dp)
                        .tapNoRipple { }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(letter = member.letter, tone = index, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = member.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = member.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = member.role,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = IcwChevron,
                        contentDescription = null,
                        tint = Color(0xFF8A8D97),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}