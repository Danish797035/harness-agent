package com.meenai.mina.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.meenai.mina.R

/** Harness Agent logo: rounded corner icon art. */
@Composable
fun HarnessLogo(size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.28f) {
    Image(
        painterResource(R.drawable.harness_launcher_art), contentDescription = null,
        modifier.size(size).clip(RoundedCornerShape(corner)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
fun MinaLogo(size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.28f) {
    HarnessLogo(size, modifier, corner)
}
