package dev.termish.ui

import androidx.compose.runtime.Composable

/** Agent 对话页使用系统 resize 语义；终端页仍保留自行处理 IME 的策略。 */
@Composable
expect fun AgentImeResizeEffect()
