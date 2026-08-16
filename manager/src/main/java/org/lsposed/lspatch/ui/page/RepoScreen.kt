package org.lsposed.lspatch.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.lsposed.lspatch.data.repository.LSPStoreSettings
import org.lsposed.lspatch.data.repository.RepoRepository
import org.lsposed.lspatch.ui.page.destinations.RepoDetailsScreenDestination

/**
 * The Store tab. A thin host around the shared Store list ([org.matrix.vector.ui.store.RepoScreen]):
 * it wires LSPatch's [RepoRepository] as the data source and [LSPStoreSettings] as the settings, and
 * keeps this destination's nav identity so `RepoScreenDestination` stays the tab target. Tapping a
 * module opens LSPatch's own details screen.
 */
@Destination
@Composable
fun RepoScreen(navigator: DestinationsNavigator) {
    val ctx = LocalContext.current
    org.matrix.vector.ui.store.RepoScreen(
        onModuleClick = { navigator.navigate(RepoDetailsScreenDestination(packageName = it)) },
        dataSource = RepoRepository.getInstance(ctx),
        settings = LSPStoreSettings,
    )
}
