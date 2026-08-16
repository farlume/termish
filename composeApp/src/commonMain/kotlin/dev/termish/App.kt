package dev.termish

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.termish.data.HostRepository
import dev.termish.ui.AppRoot

@Composable
fun App() {
    val repository = remember { HostRepository() }
    AppRoot(repository)
}
