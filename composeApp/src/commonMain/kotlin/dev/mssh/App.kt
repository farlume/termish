package dev.mssh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.mssh.data.HostRepository
import dev.mssh.ui.AppRoot

@Composable
fun App() {
    val repository = remember { HostRepository() }
    AppRoot(repository)
}
