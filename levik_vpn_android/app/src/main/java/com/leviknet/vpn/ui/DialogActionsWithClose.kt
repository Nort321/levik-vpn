package com.leviknet.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leviknet.vpn.R
import com.leviknet.vpn.ui.theme.LevikDimensions

@Composable
internal fun DialogActionsWithClose(onClose: () -> Unit, actions: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        actions()
        TextButton(
            onClick = onClose,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = LevikDimensions.ButtonHeight),
        ) {
            Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
        }
    }
}
