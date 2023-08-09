package cn.snowlie.clipboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import cn.snowlie.clipboard.ui.theme.ClipboardTheme
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import proto.ClipboardServiceGrpc
import proto.ClipboardServiceOuterClass

data class ClipboardItem(val id: String, val content: String,val deviceID: String)

data class ContentItem(val id: String, val content: String,val deviceID: String,val server: String,val port: Int)

@SuppressLint("HardwareIds")
class MainActivity : ComponentActivity() {
    val contents: MutableState<List<ClipboardItem?>> = mutableStateOf(listOf())

    //temporary server for testing
    private val server = "6.tcp.ngrok.io"
    private val port = 11744

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

    @SuppressLint("PrivateResource")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun sendNotification(title:String,message: String) {
        val notificationId = 1

        val channelId = R.string.default_notification_channel_id.toString()
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(androidx.core.R.drawable.notify_panel_notification_icon_bg)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "clipboard",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "No permission", Toast.LENGTH_SHORT).show()
            return
        }
        NotificationManagerCompat.from(this).notify(notificationId, builder.build())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, ClipboardService::class.java)
        startService(serviceIntent)

        setContent {
            App(contents, server, port,deviceID)
        }

        subscribeObserver = object : StreamObserver<ClipboardServiceOuterClass.ClipboardMessage> {
            override fun onNext(value: ClipboardServiceOuterClass.ClipboardMessage?) {
                value?.let {
                    when (value.operation) {
                        "create" -> {
                            contents.value = value.itemsList.map { clipboard ->
                                ClipboardItem(id = clipboard.id, content = clipboard.content,deviceID = clipboard.deviceId)
                            }+contents.value
                            if (value.itemsList[0].deviceId!= getDeviceId())sendNotification("New clipboard added",value.itemsList[0].content.toString())
                        }
                        "delete" -> {
                            val deleteItems=value.itemsList.map { clipboard ->
                                ClipboardItem(id = clipboard.id, content = clipboard.content,deviceID = clipboard.deviceId)
                            }
                            for (item in deleteItems) {
                                contents.value = contents.value.filter { it?.id != item.id }
                            }
                            if (value.itemsList[0].deviceId!= getDeviceId())sendNotification("New clipboard deleted",value.itemsList[0].content.toString())
                        }
                        "update" -> {
                            val updateItems=value.itemsList.map { clipboard ->
                                ClipboardItem(id = clipboard.id, content = clipboard.content,deviceID = clipboard.deviceId)
                            }
                            for (item in updateItems) {
                                contents.value = contents.value.map { if (it?.id == item.id) ClipboardItem(id = item.id, content = item.content,deviceID=deviceID) else it }
                            }
                            if (value.itemsList[0].deviceId!= getDeviceId())sendNotification("New clipboard updated",value.itemsList[0].content.toString())
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
    val chosenText: MutableState<ContentItem?> = mutableStateOf(ContentItem("", "", "", "", 0))


    ClipboardTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold{
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
                            onConfirm = { submitText->
                                if (submitText.isEmpty()) {

                                    Toast.makeText(context, "Input is empty!", Toast.LENGTH_SHORT).show()

                                } else if (submitText.isNotEmpty()) {
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
                                contentItem = ContentItem(
                                    id = chosenText.value!!.id,
                                    content = chosenText.value!!.content,
                                    deviceID = deviceID,
                                    server = server,
                                    port = port
                                ),
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
                    Column(
                        modifier = Modifier.padding(bottom = 20.dp).fillMaxSize()
                    ) {
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
    onConfirm: (String) -> Unit,
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
fun DetailBox(onDismiss: () -> Unit = {}, contentItem: ContentItem, onConfirm: () -> Unit) {
    val inputText= remember { mutableStateOf(contentItem.content) }
    val modify= remember { mutableStateOf(false) }
    val rawValue=contentItem.content

    if(modify.value.not())AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Detail Box") },
        text = { Text(text = contentItem.content) },
        dismissButton = {
            Button(onClick = {
                modify.value=true
            }) {
                Text("Modify")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("OK")
            }
        }
    )else{
        SubmitBox(
            onDismiss = onDismiss,
            text = inputText.value,
            onTextChange = { inputText.value = it },
            onConfirm = { it ->
                if(it==rawValue){
                    modify.value=false
                    return@SubmitBox
                }
                runBlocking {
                    if (it.isEmpty()) {
                        Toast.makeText(LocalContext.current, "Input is empty!", Toast.LENGTH_SHORT).show()
                        return@runBlocking
                    }
                    val channel: ManagedChannel =
                        ManagedChannelBuilder.forAddress(contentItem.server, contentItem.port)
                            .usePlaintext()
                            .build()

                    val stub: ClipboardServiceGrpc.ClipboardServiceBlockingStub =
                        ClipboardServiceGrpc.newBlockingStub(channel)

                    val updateClipboardsRequest =
                        ClipboardServiceOuterClass.UpdateRequest.newBuilder()
                            .setId(contentItem.id)
                            .setNewContent(it)
                            .setDeviceId(contentItem.deviceID)
                            .build()
                    val response = stub.update(updateClipboardsRequest)

                    if (response.success) {
                        onDismiss()
                        channel.shutdown()
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDismissItem(
    id: String,
    item: String,
    chosenText: MutableState<ContentItem?>,
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
                }, label = ""
            )
            Box(Modifier.background(color))
        },
        dismissContent = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                modifier = if (isMyDevice){
                    Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp, bottom = 10.dp, start = 60.dp, end = 20.dp)
                    .height(50.dp)
                    .width(300.dp)
                    .align(Alignment.CenterVertically)
                    .clickable {
                        chosenText.value = ContentItem(
                            id = id,
                            content = item,
                            deviceID = getDeviceId(),
                            server = chosenText.value!!.server,
                            port = chosenText.value!!.port)
                        showDetails.value = true
                    }
                }else{
                    Modifier
                        .fillMaxSize()
                        .padding(top = 10.dp, bottom = 10.dp, start = 20.dp, end = 60.dp)
                        .height(50.dp)
                        .width(300.dp)
                        .align(Alignment.CenterVertically)
                        .clickable {
                            chosenText.value = ContentItem(
                                id = id,
                                content = item,
                                deviceID = getDeviceId(),
                                server = chosenText.value!!.server,
                                port = chosenText.value!!.port)
                            showDetails.value = true
                        }
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
    chosenText: MutableState<ContentItem?>,
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
                    id = item.id,
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
                                .setDeviceId(deviceID)
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

class ClipboardService : NotificationListenerService() {

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        sendNotification(sbn)
    }

    @SuppressLint("PrivateResource")
    private fun sendNotification(sbn: StatusBarNotification, channelId: String = "clipboard") {
        val notificationId = sbn.id // 通知的ID
        val notificationTitle = sbn.notification.extras.getString(Notification.EXTRA_TITLE) // 通知标题
        val notificationText = sbn.notification.extras.getString(Notification.EXTRA_TEXT) // 通知内容

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(androidx.core.R.drawable.notification_action_background)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        notificationManager.notify(notificationId, builder.build())
    }
}