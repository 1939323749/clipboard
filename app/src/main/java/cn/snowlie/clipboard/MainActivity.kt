package cn.snowlie.clipboard

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import clipboard_service.ClipboardServiceGrpc
import clipboard_service.ClipboardServiceOuterClass
import cn.snowlie.clipboard.ui.theme.ClipboardTheme
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import android.os.Build

data class ClipboardItem(val id: String, val content: String,val deviceID: String)

@SuppressLint("HardwareIds")
class MainActivity : ComponentActivity() {
    val contents: MutableState<List<ClipboardItem?>> = mutableStateOf(listOf())

    //temporary server for testing
    private val server = "0.tcp.ap.ngrok.io"
    private val port = 13577

    var channel: ManagedChannel = ManagedChannelBuilder.forAddress(server, port)
        .usePlaintext()
        .build()

    var stub: ClipboardServiceGrpc.ClipboardServiceStub = ClipboardServiceGrpc.newStub(channel)

    // Getting clipboards
    private val getClipboardsRequest: ClipboardServiceOuterClass.GetClipboardsRequest = ClipboardServiceOuterClass.GetClipboardsRequest.newBuilder()
        .build()
    private val responseObserver = object : StreamObserver<ClipboardServiceOuterClass.GetClipboardsResponse> {
        override fun onNext(value: ClipboardServiceOuterClass.GetClipboardsResponse) {
            contents.value = value.clipboardsList.map{ clipboard ->
                ClipboardItem(id = clipboard.id, content = clipboard.content,deviceID = clipboard.deviceId)
            }
        }

        override fun onError(t: Throwable) {
            channel.shutdown()
            channel = ManagedChannelBuilder.forAddress(server, port)
                .usePlaintext()
                .build()
            stub = ClipboardServiceGrpc.newStub(channel)
            val resetClipboardsRequest = ClipboardServiceOuterClass.GetClipboardsRequest.newBuilder()
                .build()
            stub.getClipboards(resetClipboardsRequest, this)
        }

        override fun onCompleted() {
            // Handle completion here
        }
    } as StreamObserver<ClipboardServiceOuterClass.GetClipboardsResponse>

    private val subscribeClipboardRequest: ClipboardServiceOuterClass.SubscribeClipboardRequest = ClipboardServiceOuterClass.SubscribeClipboardRequest.newBuilder().build()

    private lateinit var subscribeObserver: StreamObserver<ClipboardServiceOuterClass.ClipboardMessage>

    private val deviceID= getDeviceId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            App(contents, server, port,deviceID)
        }

        subscribeObserver = object : StreamObserver<ClipboardServiceOuterClass.ClipboardMessage> {
            override fun onNext(value: ClipboardServiceOuterClass.ClipboardMessage?) {
                value?.let {
                    if (value.operation=="create") {
                            contents.value = value.itemsList.map { clipboard ->
                                ClipboardItem(id = clipboard.id, content = clipboard.content,deviceID = clipboard.deviceId)
                            }+contents.value
                    } else if (value.operation=="delete") {
                        val deleteItems=value.itemsList.map { clipboard ->
                            ClipboardItem(id = clipboard.id, content = clipboard.content,deviceID = clipboard.deviceId)
                        }
                        for (item in deleteItems) {
                            contents.value = contents.value.filter { it?.id != item.id }
                        }
                    }
                    println(value)
                }
            }

            override fun onError(t: Throwable?) {
                channel.shutdown()
                channel = ManagedChannelBuilder.forAddress(server, port)
                    .usePlaintext()
                    .build()

                stub = ClipboardServiceGrpc.newStub(channel)

                val resubscribeClipboardRequest =
                    ClipboardServiceOuterClass.SubscribeClipboardRequest.newBuilder().build()

                stub.subscribeClipboard(resubscribeClipboardRequest, this)
            }

            override fun onCompleted() {
                // Handle completion here
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                stub.getClipboards(getClipboardsRequest, responseObserver)
                stub.subscribeClipboard(subscribeClipboardRequest, subscribeObserver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnrememberedMutableState", "UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App(
    contents: MutableState<List<ClipboardItem?>> = mutableStateOf(listOf()),
    server: String,
    port: Int,
    deviceID: String
) {
    val context = LocalContext.current

    val showDetails :MutableState<Boolean?> = remember { mutableStateOf(false) }
    var showSubmitBox by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val chosenText :MutableState<String?> = remember { mutableStateOf("") }
    ClipboardTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showSubmitBox) {
                        SubmitBox(
                            onDismiss = { showSubmitBox = false },
                            text = inputText,
                            onTextChange = { inputText = it },
                            onConfirm = { submitText ->
                                if (submitText.isEmpty()) {

                                    Toast.makeText(context, "Input is empty!", Toast.LENGTH_SHORT).show()

                                } else {
                                    runBlocking {
                                        try {
                                            val channel: ManagedChannel =
                                                ManagedChannelBuilder.forAddress(server, port)
                                                    .usePlaintext()
                                                    .build()

                                            val stub: ClipboardServiceGrpc.ClipboardServiceBlockingStub =
                                                ClipboardServiceGrpc.newBlockingStub(channel)

                                            val createClipboardsRequest =
                                                ClipboardServiceOuterClass.CreateClipboardsRequest.newBuilder()
                                                    .addValues(submitText)
                                                    .setDeviceId(deviceID)
                                                    .build()
                                            val response = stub.createClipboards(createClipboardsRequest)

                                            if (response.idsList.isNotEmpty()) {
                                                showSubmitBox = false
                                                channel.shutdown()
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            Toast.makeText(context, "Network error or server error", Toast.LENGTH_SHORT)
                                                .show()
                                        } finally {
                                            inputText = ""
                                        }
                                    }
                                }
                            }
                        )
                    }
                    if (showDetails.value == true) {
                        Dialog(onDismissRequest = { showDetails.value = false }) {
                            DetailBox(
                                onDismiss = { showDetails.value = false },
                                text = chosenText.value!!,
                                onConfirm = { showDetails.value = false }
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Column {
                    TopAppBar(title = { Text(text = "Clipboard") }, navigationIcon = {
                        IconButton(onClick = { /*TODO*/ }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Add",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(MaterialTheme.colorScheme.background)
                    )
                    if (contents.value.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        SwipeToDismissListItems(
                            contents = contents,
                            chosenText = chosenText,
                            showDetails = showDetails,
                            server = server,
                            port = port,
                            deviceID = deviceID
                        )
                    }
                }
                SmallFloatingActionButton(
                    onClick = { showSubmitBox = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add",
                        tint = MaterialTheme.colorScheme.onSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun SubmitBox(
    onDismiss: () -> Unit = {}, text: String, onTextChange: (String) -> Unit,
    onConfirm: (String) -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Submit Box") },
        text = {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) {
                Text("OK")
            }
        }
    )
}

@Composable
fun DetailBox(onDismiss: () -> Unit = {}, text: String, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Detail Box") },
        text = { Text(text = text) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("OK")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDismissItem(
    item: String,
    chosenText: MutableState<String?>,
    showDetails: MutableState<Boolean?>,
    dismissState: DismissState,
    isMyDevice: Boolean,
) {
    SwipeToDismiss(
        state = dismissState,
        directions = setOf(DismissDirection.StartToEnd, DismissDirection.EndToStart),
        background = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    DismissValue.Default -> Color.Transparent
                    DismissValue.DismissedToEnd -> Color.Red
                    DismissValue.DismissedToStart -> Color.Green
                }
            )
            Box(Modifier.background(color))
        },
        dismissContent = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp, bottom = 10.dp, start = 20.dp, end = 20.dp)
                    .height(50.dp)
                    .width(300.dp)
                    .align(Alignment.CenterVertically)
                    .clickable {
                        chosenText.value = item
                        showDetails.value = true
                    },
                color = if (isMyDevice){MaterialTheme.colorScheme.primary} else {MaterialTheme.colorScheme.onSecondary}
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = item,
                        modifier = Modifier.padding(start = 20.dp)
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDismissListItems(
    contents: MutableState<List<ClipboardItem?>>,
    chosenText: MutableState<String?>,
    showDetails: MutableState<Boolean?>,
    server: String,
    port: Int,
    deviceID: String
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(contents.value) { index, item ->
            val dismissState = rememberDismissState()
            if (item != null) {
                SwipeToDismissItem(
                    item = item.content,
                    chosenText = chosenText,
                    showDetails = showDetails,
                    dismissState = dismissState,
                    isMyDevice = item.deviceID == deviceID
                )
            }
            LaunchedEffect(key1 = dismissState.currentValue) {
                if (dismissState.currentValue == DismissValue.DismissedToEnd ||
                    dismissState.currentValue == DismissValue.DismissedToStart
                ) {
                    runBlocking {
                        try {
                            // Create a channel to the server
                            val channel: ManagedChannel = ManagedChannelBuilder
                                .forAddress(server, port)
                                .usePlaintext()
                                .build()

                            // Create a stub for making requests
                            val stub: ClipboardServiceGrpc.ClipboardServiceBlockingStub =
                                ClipboardServiceGrpc.newBlockingStub(channel)

                            // Prepare the request
                            val deleteClipboardsRequest = ClipboardServiceOuterClass.DeleteClipboardsRequest
                                .newBuilder()
                                .addIds(item?.id ) // replace `idToDelete` with the ID of the clipboard to delete
                                .build()

                            // Make the request
                            val response = stub.deleteClipboards(deleteClipboardsRequest)

                            if (response.success) {
                                // The clipboard was successfully deleted
                                println("Clipboard deleted successfully!")
                            } else {
                                // The clipboard could not be deleted
                                println("Clipboard deletion failed.")
                            }

                            // Don't forget to shut down the channel when you're done
                            channel.shutdown()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            println("Network error or server error")
                        }
                    }

                    contents.value = contents.value.filter { it != item }
                    dismissState.reset()
                }
            }
        }
    }
}

@SuppressLint("HardwareIds")
fun getDeviceId(): String {
    val manufacturer = Build.MANUFACTURER
    val model = Build.MODEL
    val fingerprint = Build.FINGERPRINT
    return "$manufacturer-$model-$fingerprint"
}