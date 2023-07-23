package cn.snowlie.clipboard

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import clipboard_service.ClipboardServiceGrpc
import clipboard_service.ClipboardServiceOuterClass
import cn.snowlie.clipboard.ui.theme.ClipboardTheme
import kotlinx.coroutines.*
import io.grpc.ManagedChannelBuilder
import io.grpc.ManagedChannel
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    private val contents = mutableStateOf(listOf<String?>())

    //temporary server for testing
    private val server = "0.tcp.ap.ngrok.io"
    private val port = 18273

    var channel: ManagedChannel = ManagedChannelBuilder.forAddress(server, port)
        .usePlaintext()
        .build()

    var stub: ClipboardServiceGrpc.ClipboardServiceStub = ClipboardServiceGrpc.newStub(channel)

    // Getting clipboards
    private val getClipboardsRequest: ClipboardServiceOuterClass.GetClipboardsRequest = ClipboardServiceOuterClass.GetClipboardsRequest.newBuilder()
        .build()
    private val responseObserver = object : StreamObserver<ClipboardServiceOuterClass.GetClipboardsResponse> {
        override fun onNext(value: ClipboardServiceOuterClass.GetClipboardsResponse) {
            contents.value = value.valuesList
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(contents, server, port)
        }

        subscribeObserver = object : StreamObserver<ClipboardServiceOuterClass.ClipboardMessage> {
            override fun onNext(value: ClipboardServiceOuterClass.ClipboardMessage?) {
                value?.let {
                    contents.value = listOf(it.value) + contents.value
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

                stub.subscribeClipboard(resubscribeClipboardRequest, subscribeObserver)
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
fun App(contents: MutableState<List<String?>> = mutableStateOf(listOf()), server: String, port: Int) {
    val context = LocalContext.current
    ClipboardTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var showDetails by remember { mutableStateOf(false) }
            var showSubmitBox by remember { mutableStateOf(false) }
            var inputText by remember { mutableStateOf("") }
            var chosenText by remember { mutableStateOf("") }
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
                    if (showDetails) {
                        DetailBox(
                            onDismiss = { showDetails = false },
                            text = chosenText,
                            onConfirm = { showDetails = false })
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Column {
                    SmallTopAppBar(title = { Text(text = "Clipboard") }, navigationIcon = {
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
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            items(contents.value.size) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxSize()
                                        .padding(top = 10.dp, bottom = 10.dp, start = 20.dp, end = 20.dp).height(50.dp)
                                        .width(300.dp).align(Alignment.CenterHorizontally),
                                    color = MaterialTheme.colorScheme.primary,
                                    onClick = {
                                        chosenText = contents.value[it]!!
                                        showDetails = true
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center
                                    ) { Text(text = contents.value[it]!!, modifier = Modifier.padding(start = 20.dp)) }
                                }
                            }
                        }
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



