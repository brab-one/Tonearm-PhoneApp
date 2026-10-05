package io.github.deadeyebarb.tonearm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.AuthMethod
import io.github.deadeyebarb.tonearm.ui.ServerEditRoute
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import kotlinx.coroutines.launch

@Composable
fun ServersScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val list by c.servers.state.collectAsStateWithLifecycle()
    val active = list?.active
    DetailScaffold(title = "Servers") {
        LazyColumn(Modifier.fillMaxSize()) {
            items(list?.servers.orEmpty(), key = { it.id }) { server ->
                val select = { c.scope.launch { c.servers.setActive(server.id) }; Unit }
                ListItem(
                    modifier = Modifier.clickable(onClick = select),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { RadioButton(selected = server.id == active?.id, onClick = select) },
                    headlineContent = { Text(server.name) },
                    supportingContent = {
                        val who = if (server.auth == AuthMethod.API_KEY) "API key" else server.username
                        Text(listOfNotNull(who, server.baseUrl, "mTLS".takeIf { server.clientCert != null }).joinToString(" · "))
                    },
                    trailingContent = {
                        IconButton(onClick = { actions.navigate(ServerEditRoute(server.id)) }) { Icon(Icons.Rounded.Edit, "Edit") }
                    },
                )
            }
            item {
                HudButton("Add server", { actions.navigate(ServerEditRoute()) }, Modifier.fillMaxWidth().padding(16.dp), icon = Icons.Rounded.Add)
            }
        }
    }
}
