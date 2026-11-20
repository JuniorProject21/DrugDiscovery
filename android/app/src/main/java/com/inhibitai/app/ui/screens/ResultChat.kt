package com.inhibitai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.data.Compound
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.theme.*

/** Questions worth a tap, so the user doesn't have to think of one to get started. */
private val SUGGESTIONS = listOf(
    "Why did it get this score?",
    "What does the reliability mean?",
    "Is this compound drug-like?",
    "What should I test next?",
)

/**
 * "Learn more" on a result: a conversation about this one compound.
 *
 * The answers come from a language model on the server, which is given the numbers InhibitAI
 * computed and told to explain them. It never produces the prediction itself, and the sheet says
 * so, because an assistant that sounded authoritative about a number it invented would undo the
 * care taken everywhere else in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultChatSheet(vm: InhibitViewModel, compound: Compound, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var question by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(compound.id) { vm.openChat(compound) }
    // Keep the newest answer in view as the conversation grows.
    LaunchedEffect(vm.chatMessages.size, vm.chatBusy) {
        if (vm.chatMessages.isNotEmpty()) listState.animateScrollToItem(vm.chatMessages.size)
    }

    ModalBottomSheet(
        onDismissRequest = { vm.closeChat(); onDismiss() },
        sheetState = sheetState,
        containerColor = BgElevated,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CardBorder) },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ask about ${compound.name}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        color = TextPrimary)
                    Text(
                        "${compound.status.label} · score ${"%.3f".format(compound.score)}",
                        fontSize = 14.sp, color = TextSecondary,
                    )
                }
                StatusPill(compound.status, compact = true)
            }
            VSpace(14.dp)

            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(vm.chatMessages) { message ->
                    ChatBubble(message.text, fromUser = message.fromUser)
                }
                if (vm.chatBusy) {
                    item { TypingRow() }
                }
                vm.chatError?.let { error ->
                    item { ChatErrorRow(error, onRetry = { vm.retryChat() }) }
                }
                item { VSpace(4.dp) }
            }

            // Suggestions only while the conversation is short, so they don't crowd the answers.
            if (vm.chatMessages.size <= 2 && !vm.chatBusy) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SUGGESTIONS.forEach { suggestion ->
                        ChoicePill(suggestion, selected = false) { vm.askChat(suggestion) }
                    }
                }
            }

            ChatInput(
                value = question,
                onValueChange = { question = it },
                enabled = !vm.chatBusy,
                onSend = {
                    val text = question.trim()
                    if (text.isNotEmpty()) {
                        vm.askChat(text)
                        question = ""
                    }
                },
            )
            Text(
                "Answers come from an open-source language model, given this compound's numbers. " +
                    "It explains the result; it doesn't produce it, and it can still be wrong.",
                fontSize = 12.sp, lineHeight = 17.sp, color = TextMuted,
                modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ChatBubble(text: String, fromUser: Boolean) {
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (fromUser) 18.dp else 4.dp,
        bottomEnd = if (fromUser) 4.dp else 18.dp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(if (fromUser) Purple else CardBg)
                .border(1.dp, if (fromUser) Color.Transparent else CardBorder, shape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Text(
                text, fontSize = 15.sp, lineHeight = 22.sp,
                color = if (fromUser) Color.White else TextPrimary,
            )
        }
    }
}

@Composable
private fun TypingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = PurpleLight, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        HSpace(10.dp)
        Text("Thinking…", fontSize = 14.sp, color = TextSecondary)
    }
}

@Composable
private fun ChatErrorRow(message: String, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Yellow.copy(alpha = 0.10f))
            .border(1.dp, Yellow.copy(alpha = 0.45f), shape)
            .padding(14.dp),
    ) {
        Text(message, fontSize = 14.sp, lineHeight = 20.sp, color = TextPrimary)
        VSpace(8.dp)
        Text(
            "Try again", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PurpleLight,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(4.dp),
        )
    }
}

@Composable
private fun ChatInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FieldBg)
            .border(1.dp, FieldBorder, shape)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (value.isEmpty()) Text("Ask a question…", color = TextMuted, fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(PurpleLight),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ask a question about this result" },
            )
        }
        val ready = enabled && value.isNotBlank()
        IconButton(onClick = onSend, enabled = ready) {
            Icon(
                Icons.AutoMirrored.Filled.Send, "Send",
                tint = if (ready) PurpleLight else TextMuted, modifier = Modifier.size(26.dp),
            )
        }
    }
}
