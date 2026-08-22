package dev.termish.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染器（纯 Kotlin + AnnotatedString，零第三方依赖）：
 * 覆盖常用语法——标题 / 代码块 / 行内代码 / 粗体 / 斜体 / 链接 / 引用 /
 * 无序·有序列表 / 分隔线。表格与嵌套语法保持源码原样（不做表格渲染）。
 * 供 SFTP 文件管理 Markdown 预览「渲染模式」使用；渲染结果可复制。
 */
fun renderMarkdown(
    src: String,
    baseFontSize: TextUnit = 14.sp,
    codeBg: Color = Color(0x14000000),
    linkColor: Color = Color(0xFF1E88E5),
): AnnotatedString {
    val b = AnnotatedString.Builder()
    var inCodeBlock = false
    for (line in src.lines()) {
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                inCodeBlock = !inCodeBlock
                b.append("\n")
            }
            inCodeBlock -> b.withStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = codeBg,
                fontSize = baseFontSize * 0.92f,
            )) { b.append(line) }
            else -> renderBlockLine(b, line, trimmed, baseFontSize, codeBg, linkColor)
        }
        b.append("\n")
    }
    return b.toAnnotatedString()
}

private fun renderBlockLine(
    b: AnnotatedString.Builder,
    raw: String,
    trimmed: String,
    base: TextUnit,
    codeBg: Color,
    linkColor: Color,
) {
    when {
        // 标题：1-6 级（# 数越多字号越小）
        trimmed.startsWith("#") -> {
            val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
            val text = trimmed.drop(level).trimStart()
            val size = base * (1.55f - level * 0.12f)
            inline(b, text, base, codeBg, linkColor, baseStyle = SpanStyle(
                fontSize = size,
                fontWeight = FontWeight.Bold,
            ))
        }
        // 引用：前缀色块 + 斜体
        trimmed.startsWith(">") -> {
            val text = trimmed.removePrefix(">").trimStart().ifBlank { " " }
            b.withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) { b.append("▍ ") }
            inline(b, text, base, codeBg, linkColor, baseStyle = SpanStyle(fontStyle = FontStyle.Italic))
        }
        // 分隔线
        trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
            b.withStyle(SpanStyle(color = linkColor.copy(alpha = 0.4f))) { b.append("─".repeat(32)) }
        }
        // 无序列表
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
            b.withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) { b.append("• ") }
            inline(b, trimmed.drop(2), base, codeBg, linkColor)
        }
        // 有序列表
        trimmed.matches(Regex("\\d+\\.\\s.*")) -> {
            val idx = trimmed.indexOf('.')
            b.withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) { b.append("${trimmed.substring(0, idx)}. ") }
            inline(b, trimmed.substring(idx + 1).trimStart(), base, codeBg, linkColor)
        }
        // 普通段落
        else -> inline(b, raw, base, codeBg, linkColor)
    }
}

/** 行内语法：**粗体** / *斜体* / `行内代码` / [文字](链接)。 */
private fun inline(
    b: AnnotatedString.Builder,
    text: String,
    base: TextUnit,
    codeBg: Color,
    linkColor: Color,
    baseStyle: SpanStyle = SpanStyle(),
) {
    val token = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`|\\[[^\\]]+\\]\\([^)]*\\))")
    var last = 0
    for (m in token.findAll(text)) {
        b.withStyle(baseStyle) { b.append(text.substring(last, m.range.first)) }
        val tok = m.value
        when {
            tok.startsWith("**") -> b.withStyle(baseStyle + SpanStyle(fontWeight = FontWeight.Bold)) { b.append(tok.trim('*')) }
            tok.startsWith("`") -> b.withStyle(
                baseStyle + SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = base * 0.92f),
            ) { b.append(tok.trim('`')) }
            tok.startsWith("[") -> {
                val inner = tok.substring(1, tok.indexOf(']'))
                b.withStyle(baseStyle + SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { b.append(inner) }
            }
            else -> b.withStyle(baseStyle + SpanStyle(fontStyle = FontStyle.Italic)) { b.append(tok.trim('*')) }
        }
        last = m.range.last + 1
    }
    b.withStyle(baseStyle) { b.append(text.substring(last)) }
}
