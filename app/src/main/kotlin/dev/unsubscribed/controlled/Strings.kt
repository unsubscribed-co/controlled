package dev.unsubscribed.controlled

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Every text the app shows, one object per language: English ([En]) and Spanish ([Es]: Colombian, tú), StackSplit's
 * pattern, so a text missing in either language doesn't compile. Android's per-app language setting picks between them.
 */
interface Strings {
    // Local network permission
    val permissionTitle: String
    val permissionBody: String
    val permissionAllow: String
    val permissionDenied: String
    val openSettings: String

    // Find and pair
    val findTitle: String
    val searching: String
    val noneFound: String
    val searchAgain: String
    val typeAddress: String
    val addressHint: String
    val connect: String
    val unreachable: String
    val rejected: String
    val noAnswer: String
    fun pairingTitle(tv: String): String
    val pairingBody: String
    /** A Google TV pairs by a code it shows: the title, what to do, the field, a wrong code, and its label in the list. */
    fun codeTitle(tv: String): String
    val codeBody: String
    val codeLabel: String
    val codeWrong: String
    val googleTv: String
    /** The countdown inside the pairing ring, for TalkBack: "52 seconds left". */
    fun secondsLeft(seconds: Int): String
    val cancel: String

    // Status line under the TV's name
    val connecting: String
    val turningOn: String
    val tvOff: String
    val waiting: String
    val tvOffNoPower: String
    val lookingForTv: String
    val gtvUnreachable: String
    val noWifi: String
    /** Turn on timed out: where LG keeps the setting that lets the TV wake from the phone. */
    val wakeHint: String
    fun nowOn(name: String): String
    val certificateChanged: String
    /** A Google TV no longer has this phone paired, and its button. */
    val tvForgot: String
    val pairAgain: String
    /** A VPN carries this app's traffic and may be hiding the home network. */
    val vpnNote: String
    val pcSmooth: String
    val smoothOffer: String
    val smoothOfferBody: String
    val openOnPc: String
    val smoothDeclinedBody: String
    val askPcAgain: String
    val notNow: String
    val smoothPairTitle: String
    val smoothPairBody: String
    val forgetTv: String
    val privacy: String
    val sourceCode: String
    val rename: String
    val renameTitle: String
    val tvName: String
    val save: String

    // Keys
    val powerOn: String
    val powerOff: String
    val more: String
    val back: String
    val home: String
    val menu: String
    val ok: String
    val up: String
    val down: String
    val left: String
    val right: String
    val volumeUp: String
    val volumeDown: String
    /** The readout between volume up and down, which mutes on tap. */
    fun volumeReadout(level: Int?, muted: Boolean): String
    fun volume(level: Int): String
    val muted: String
    val channelUp: String
    val channelDown: String
    val channel: String
    val play: String
    val pause: String
    val keyboard: String
    val touchpad: String
    val inputs: String

    // Apps
    val apps: String
    val allApps: String
    val pinned: String
    val mostUsed: String
    val everythingElse: String
    val edit: String
    val done: String
    val editHint: String
    fun pin(app: String): String
    fun unpin(app: String): String
    fun moveEarlier(app: String): String
    fun moveLater(app: String): String
    val removeFromMostUsed: String
    fun removedFromMostUsed(app: String): String
    val undo: String
    val searchApps: String
    fun noAppsMatch(query: String): String
    val appsLoading: String

    // Inputs, keyboard, touchpad
    val notConnected: String
    val onNow: String
    val typeHint: String
    val enter: String
    val deleteChar: String
    /** Type: puts the phone's clipboard into the TV's field. */
    val pasteFromPhone: String
    val touchpadHint: String
    val close: String

    val openingOnTv: String
    val noLink: String
    val pairFirst: String
    val bleKey: String
    val bleKeyLabel: String
    val bleKeyHelp: String
    val bleKeyInvalid: String
    // Controllers and the PC
    val controllers: String
    val switchController: String
    val addTv: String
    val addPc: String
    val defaultController: String
    val makeDefault: String
    val remove: String
    val backToControllers: String
    val pcWaiting: String
    val pcConnected: String
    val pcNoBluetooth: String
    /** The phone refuses to act as a Bluetooth keyboard (some makers turn it off). */
    val pcUnsupported: String
    val pcBusy: String
    val adminBlocked: String
    val secureBlocked: String
    val useBluetooth: String
    val fixKeys: String
    val fixKeysBody: String
    /** A computer paired before has the phone saved without the keyboard: remove it there and add it again. */
    /** Adding a PC is taking long: the likely miss. */
    val addPcSlow: String
    val pcPermission: String
    val addPcTitle: String
    val addPcSteps: String
    val makeVisible: String
    val trackpadHint: String
    val leftClick: String
    val rightClick: String
    val typeOnPc: String
    /** Characters the PC's keyboard layout can't type, so they didn't reach it. */
    fun notTyped(chars: String): String
    val esc: String
    val tab: String
    val toolsWindows: String
    val toolsTabs: String
    val toolsEdit: String
    val closeWindowName: String
    val minimize: String
    val maximize: String
    val snapLeft: String
    val snapRight: String
    val previousTab: String
    val nextTab: String
    val closeTab: String
    val closeTabName: String
    val newTab: String
    val reopenTab: String
    val addressBar: String
    val goBack: String
    val reload: String
    val copy: String
    val paste: String
    val cut: String
    val selectAll: String
    val redo: String
    val find: String
    val deleteKey: String
    val toolsKeys: String
    val toolsMedia: String
    val toolsSystem: String
    val showDesktop: String
    val files: String
    val fileExplorer: String
    val pcSettings: String
    val tasks: String
    val taskManager: String
    val runDialog: String
    val lockPc: String
    val snip: String
    val snipName: String
    val ctrlAltDel: String
    val toolsScreen: String
    val pcOnly: String
    val pcOnlyName: String
    val duplicate: String
    val extend: String
    val secondOnly: String
    val secondOnlyName: String
    val start: String
    val nextWindow: String
    val allWindows: String
    val closeWindow: String
    val switchWindow: String
    val mute: String
    val playPause: String
    val previous: String
    val next: String
    val renamePc: String
    val pcName: String
    val keyboardLayout: String
    val keyboardLayoutHint: String
    /** Asked once, right after a PC is added. */
    val askLayout: String
    val layoutLatAm: String
    val layoutSpain: String
    val layoutUs: String
    // Dev self-update
    val newVersion: String
    val update: String
}

object En : Strings {
    override val permissionTitle = "Find your TV"
    override val permissionBody = "Controlled talks to your TV over your home Wi-Fi. Android will ask to let it find and connect to devices on your local network. Nothing leaves your home."
    override val permissionAllow = "Continue"
    override val permissionDenied = "Without this permission Controlled can't reach your TV."
    override val openSettings = "Open settings"

    override val findTitle = "Pick your TV"
    override val searching = "Looking for TVs on this Wi-Fi…"
    override val noneFound = "No TV found. Check that the TV is on and on the same Wi-Fi, or type its address."
    override val searchAgain = "Search again"
    override val typeAddress = "TV address"
    override val addressHint = "For example 192.168.1.12 (TV Settings > Network)"
    override val connect = "Connect"
    override val unreachable = "Couldn't reach the TV at that address."
    override val rejected = "The TV said no. Try again and choose Accept on the TV."
    override val noAnswer = "No answer from the TV. Try again and choose Accept on the TV."
    override fun pairingTitle(tv: String) = "Accept on $tv"
    override val pairingBody = "Your TV is asking whether to let this phone control it. Choose Accept with the TV's remote. You only do this once."
    override fun codeTitle(tv: String) = "Type the code on $tv"
    override val codeBody = "Your TV is showing a 6-character code. Type it here to let this phone control it. You only do this once."
    override val codeLabel = "Code on the TV"
    override val codeWrong = "That isn't the code on the TV. Check it and type it again."
    override val googleTv = "Google TV"
    override fun secondsLeft(seconds: Int) = if (seconds == 1) "1 second left" else "$seconds seconds left"
    override val cancel = "Cancel"

    override val connecting = "Connecting…"
    override val turningOn = "Turning on…"
    override val tvOff = "TV is off. Tap to turn it on"
    override val waiting = "Waiting for your TV. Tap to turn it on"
    override val tvOffNoPower = "Waiting for your TV. Tap to try now"
    override val lookingForTv = "Looking for your TV on this Wi-Fi…"
    override val gtvUnreachable = "Can't reach the TV. Check it's on this phone's Wi-Fi; if it is, on the TV forget the Wi-Fi network and join it again. Tap to retry"
    override val noWifi = "No Wi-Fi. Connect to your home Wi-Fi"
    override val wakeHint = "The TV didn't wake. On the TV, turn on Settings > General > Devices > Mobile TV On > Turn on via Wi-Fi"
    override fun nowOn(name: String) = "On now: $name"
    override val certificateChanged = "This TV's security certificate changed. If you replaced or reset the TV, forget it and pair again."
    override val tvForgot = "This TV no longer knows this phone: it was reset, or its remotes were removed. Pair it again."
    override val pairAgain = "Pair again"
    override val pcSmooth = "Smooth mode · Wi-Fi"
    override val smoothOffer = "Smooth mode"
    override val smoothOfferBody = "For a smoother pointer, install the free Controlled for PC app on this PC. It works over your Wi-Fi; Bluetooth stays as the backup.\n\nOpen on the PC brings up its Microsoft Store page there: just click Install."
    override val openOnPc = "Open on the PC"
    override val smoothDeclinedBody = "The PC didn't allow this phone, so it stays on Bluetooth. To use smooth mode, ask again and click Yes on the PC when it shows the same code as here."
    override val askPcAgain = "Ask the PC again"
    override val notNow = "Not now"
    override val smoothPairTitle = "Allow this phone on the PC"
    override val smoothPairBody = "The PC is asking to allow this phone. Click Yes there if it shows this same code:"
    override val vpnNote = "Can't reach the TV. A VPN is on in this phone: if it covers your home network, exclude Controlled in the VPN app."
    override val forgetTv = "Forget this TV"
    override val privacy = "Privacy policy"
    override val sourceCode = "Source code"
    override val rename = "Rename"
    override val renameTitle = "Name this TV"
    override val tvName = "TV name"
    override val save = "Save"

    override val powerOn = "Turn on"
    override val powerOff = "Turn off"
    override val more = "More options"
    override val back = "Back"
    override val home = "Home"
    override val menu = "Settings"
    override val ok = "OK"
    override val up = "Up"
    override val down = "Down"
    override val left = "Left"
    override val right = "Right"
    override val volumeUp = "Volume up"
    override val volumeDown = "Volume down"
    override fun volumeReadout(level: Int?, muted: Boolean) = when {
        muted -> "Muted. Tap to unmute"
        level == null -> "Mute"
        else -> "Volume $level. Tap to mute"
    }
    override fun volume(level: Int) = "Volume $level"
    override val muted = "Muted"
    override val channelUp = "Next channel"
    override val channelDown = "Previous channel"
    override val channel = "CH"
    override val play = "Play"
    override val pause = "Pause"
    override val keyboard = "Type"
    override val touchpad = "Touchpad"
    override val inputs = "Inputs"

    override val apps = "Apps"
    override val allApps = "All apps"
    override val pinned = "Pinned"
    override val mostUsed = "Most used"
    override val everythingElse = "Everything else"
    override val edit = "Edit"
    override val done = "Done"
    override val editHint = "Tap an app to pin or unpin it. Pinned apps stay first, in your order."
    override fun pin(app: String) = "Pin $app"
    override fun unpin(app: String) = "Unpin $app"
    override fun moveEarlier(app: String) = "Move $app earlier"
    override fun moveLater(app: String) = "Move $app later"
    override val removeFromMostUsed = "Remove from Most used"
    override fun removedFromMostUsed(app: String) = "Removed $app from Most used"
    override val undo = "Undo"
    override val searchApps = "Search apps"
    override fun noAppsMatch(query: String) = "No app matches “$query”"
    override val appsLoading = "Your TV's apps appear here once it's connected."

    override val notConnected = "Nothing connected"
    override val onNow = "On now"
    override val typeHint = "Type here, it appears on the TV as you type"
    override val enter = "Enter"
    override val deleteChar = "Delete"
    override val pasteFromPhone = "Paste from phone"
    override val touchpadHint = "Drag to move, tap to click, two fingers to scroll"
    override val close = "Close"

    override val openingOnTv = "Opening on your TV"
    override val noLink = "There's no link to open on the TV"
    override val pairFirst = "Pick your TV first, then share again"
    override val bleKey = "Bluetooth wake key"
    override val bleKeyLabel = "16 hex digits"
    override val bleKeyHelp = "Advanced. With the key LG ThinQ uses for your TV, Turn on also works over Bluetooth, even when the TV has been off for hours. Leave empty to turn it off."
    override val bleKeyInvalid = "It must be 16 hex digits (0-9, a-f)"
    override val controllers = "Your controllers"
    override val switchController = "Switch controller"
    override val addTv = "Add a TV"
    override val addPc = "Add a PC"
    override val defaultController = "Default"
    override val makeDefault = "Make default"
    override val remove = "Remove"
    override val backToControllers = "Back"
    override val pcWaiting = "Waiting for the PC…"
    override val pcConnected = "Connected"
    override val pcNoBluetooth = "Turn on Bluetooth to use the PC"
    override val pcUnsupported = "This phone can't act as a Bluetooth keyboard, so it can't control a PC"
    override val pcBusy = "Another app on this phone is acting as its keyboard (another remote app?). Close it to control this PC"
    override val fixKeys = "Fix keyboard and mouse"
    override val adminBlocked = "Windows blocks smooth mode in administrator windows."
    override val secureBlocked = "Windows blocks smooth mode on this screen."
    override val useBluetooth = "Use Bluetooth"
    override val fixKeysBody = "If keys repeat, the wrong characters come out or the pointer acts strange, the PC kept an old setup for this phone (another remote app can cause it).\n\nOn the PC, open Settings > Bluetooth & devices and remove this phone. Then tap Add a PC and pair again."
    override val addPcSlow = "Still waiting? Keep this screen open while the PC adds the phone. If the PC already lists this phone, remove it there and add it again."
    override val pcPermission = "Controlled works as a Bluetooth keyboard and mouse for your PC. Android will ask to let it connect to nearby devices."
    override val addPcTitle = "Add a PC"
    override val addPcSteps = "For Windows PCs (its shortcuts are Windows ones).\n1. Tap Make this phone visible.\n2. On the PC: Settings > Bluetooth & devices > Add device > Bluetooth, and pick this phone.\n3. Confirm the code on both."
    override val makeVisible = "Make this phone visible"
    override val trackpadHint = "Drag to move. Tap to click. Two fingers: tap to right-click, drag to scroll. Tap, then touch and drag to select."
    override val leftClick = "Left click"
    override val rightClick = "Right click"
    override val typeOnPc = "Type on the PC"
    override fun notTyped(chars: String) = "Not typed on the PC: $chars. Its keyboard can't type them."
    override val esc = "Esc"
    override val tab = "Tab"
    override val toolsWindows = "Windows"
    override val toolsTabs = "Tabs"
    override val toolsEdit = "Edit"
    override val closeWindowName = "Close window"
    override val minimize = "Minimize"
    override val maximize = "Maximize"
    override val snapLeft = "Left half"
    override val snapRight = "Right half"
    override val previousTab = "Previous tab"
    override val nextTab = "Next tab"
    override val closeTab = "Close"
    override val closeTabName = "Close tab"
    override val newTab = "New tab"
    override val reopenTab = "Reopen"
    override val addressBar = "Address"
    override val goBack = "Back"
    override val reload = "Reload"
    override val copy = "Copy"
    override val paste = "Paste"
    override val cut = "Cut"
    override val selectAll = "Select all"
    override val redo = "Redo"
    override val find = "Find"
    override val deleteKey = "Del"
    override val toolsKeys = "Keys"
    override val toolsMedia = "Media"
    override val toolsSystem = "System"
    override val showDesktop = "Desktop"
    override val files = "Files"
    override val fileExplorer = "File Explorer"
    override val pcSettings = "Settings"
    override val tasks = "Tasks"
    override val taskManager = "Task Manager"
    override val runDialog = "Run"
    override val lockPc = "Lock"
    override val snip = "Snip"
    override val snipName = "Screenshot of an area"
    override val ctrlAltDel = "Ctrl+Alt+Del"
    override val toolsScreen = "Screen"
    override val pcOnly = "PC only"
    override val pcOnlyName = "PC screen only"
    override val duplicate = "Duplicate"
    override val extend = "Extend"
    override val secondOnly = "Second"
    override val secondOnlyName = "Second screen only"
    override val start = "Start"
    override val nextWindow = "Next"
    override val allWindows = "All windows"
    override val closeWindow = "Close"
    override val switchWindow = "Switch"
    override val mute = "Mute"
    override val playPause = "Play or pause"
    override val previous = "Previous"
    override val next = "Next"
    override val renamePc = "Name this PC"
    override val pcName = "PC name"
    override val keyboardLayout = "Keyboard layout"
    override val keyboardLayoutHint = "Pick the one this PC uses in Windows, so what you type comes out right."
    override val askLayout = "What keyboard does this PC use?"
    override val layoutLatAm = "Spanish (Latin America)"
    override val layoutSpain = "Spanish (Spain)"
    override val layoutUs = "English (US)"
    override val newVersion = "A new version is available"
    override val update = "Update"
}

object Es : Strings {
    override val permissionTitle = "Encuentra tu televisor"
    override val permissionBody = "Controlled se comunica con tu televisor a través del Wi-Fi de tu casa. Android te pedirá permiso para buscar y conectarse a dispositivos en tu red local. Nada sale de tu casa."
    override val permissionAllow = "Continuar"
    override val permissionDenied = "Sin este permiso Controlled no puede conectarse a tu televisor."
    override val openSettings = "Abrir ajustes"

    override val findTitle = "Elige tu televisor"
    override val searching = "Buscando televisores en este Wi-Fi…"
    override val noneFound = "No encontramos ningún televisor. Revisa que esté encendido y en el mismo Wi-Fi, o escribe su dirección."
    override val searchAgain = "Buscar de nuevo"
    override val typeAddress = "Dirección del TV"
    override val addressHint = "Por ejemplo 192.168.1.12 (Configuración > Red)"
    override val connect = "Conectar"
    override val unreachable = "No fue posible conectarse al televisor en esa dirección."
    override val rejected = "El televisor dijo que no. Intenta de nuevo y elige Aceptar en el TV."
    override val noAnswer = "El televisor no respondió. Intenta de nuevo y elige Aceptar en el TV."
    override fun pairingTitle(tv: String) = "Acepta en $tv"
    override val pairingBody = "Tu televisor está preguntando si este celular puede controlarlo. Elige Aceptar con el control del televisor. Solo tienes que hacerlo una vez."
    override fun codeTitle(tv: String) = "Escribe el código de $tv"
    override val codeBody = "Tu televisor muestra un código de 6 caracteres. Escríbelo aquí para que este celular pueda controlarlo. Solo tienes que hacerlo una vez."
    override val codeLabel = "Código del televisor"
    override val codeWrong = "Ese no es el código del televisor. Revísalo y escríbelo otra vez."
    override val googleTv = "Google TV"
    override fun secondsLeft(seconds: Int) = if (seconds == 1) "Queda 1 segundo" else "Quedan $seconds segundos"
    override val cancel = "Cancelar"

    override val connecting = "Conectando…"
    override val turningOn = "Encendiendo…"
    override val tvOff = "El TV está apagado. Toca para encenderlo"
    override val waiting = "Esperando tu TV. Toca para encenderlo"
    override val tvOffNoPower = "Esperando tu TV. Toca para intentar ya"
    override val lookingForTv = "Buscando tu TV en este Wi-Fi…"
    override val gtvUnreachable = "No llegamos al TV. Revisa que esté en el mismo Wi-Fi que este celular; si lo está, en el TV olvida la red Wi-Fi y vuelve a unirte. Toca para reintentar"
    override val noWifi = "Sin Wi-Fi. Conéctate al Wi-Fi de tu casa"
    override val wakeHint = "El TV no encendió. En el TV, activa Configuración > General > Dispositivos > Encender TV con móvil > Encender por Wi-Fi"
    override fun nowOn(name: String) = "Ahora: $name"
    override val certificateChanged = "El certificado de seguridad de este televisor cambió. Si reemplazaste o restableciste el TV, olvídalo y vincúlalo de nuevo."
    override val tvForgot = "Este televisor ya no reconoce este celular: lo restablecieron o le quitaron los controles. Vincúlalo de nuevo."
    override val pairAgain = "Vincular de nuevo"
    override val pcSmooth = "Modo fluido · Wi-Fi"
    override val smoothOffer = "Modo fluido"
    override val smoothOfferBody = "Para un puntero más fluido, instala la app gratuita Controlled for PC en este PC. Funciona por tu Wi-Fi; el Bluetooth queda de respaldo.\n\nAbrir en el PC muestra allá su página de Microsoft Store: solo haz clic en Instalar."
    override val openOnPc = "Abrir en el PC"
    override val smoothDeclinedBody = "El PC no permitió este celular, así que sigue por Bluetooth. Para usar el modo fluido, pregunta de nuevo y haz clic en Sí en el PC cuando muestre el mismo código que aquí."
    override val askPcAgain = "Preguntar de nuevo al PC"
    override val notNow = "Ahora no"
    override val smoothPairTitle = "Permite este celular en el PC"
    override val smoothPairBody = "El PC pide permitir este celular. Haz clic en Sí allá si muestra este mismo código:"
    override val vpnNote = "No llegamos al televisor. Este celular tiene una VPN activa: si cubre la red de tu casa, excluye Controlled en la app de la VPN."
    override val forgetTv = "Olvidar este TV"
    override val privacy = "Política de privacidad"
    override val sourceCode = "Código fuente"
    override val rename = "Cambiar nombre"
    override val renameTitle = "Nombra este TV"
    override val tvName = "Nombre del TV"
    override val save = "Guardar"

    override val powerOn = "Encender"
    override val powerOff = "Apagar"
    override val more = "Más opciones"
    override val back = "Atrás"
    override val home = "Inicio"
    override val menu = "Ajustes"
    override val ok = "OK"
    override val up = "Arriba"
    override val down = "Abajo"
    override val left = "Izquierda"
    override val right = "Derecha"
    override val volumeUp = "Subir volumen"
    override val volumeDown = "Bajar volumen"
    override fun volumeReadout(level: Int?, muted: Boolean) = when {
        muted -> "Silenciado. Toca para reactivar el sonido"
        level == null -> "Silenciar"
        else -> "Volumen $level. Toca para silenciar"
    }
    override fun volume(level: Int) = "Volumen $level"
    override val muted = "Silenciado"
    override val channelUp = "Canal siguiente"
    override val channelDown = "Canal anterior"
    override val channel = "CH"
    override val play = "Reproducir"
    override val pause = "Pausar"
    override val keyboard = "Escribir"
    override val touchpad = "Touchpad"
    override val inputs = "Entradas"

    override val apps = "Apps"
    override val allApps = "Todas las apps"
    override val pinned = "Fijadas"
    override val mostUsed = "Más usadas"
    override val everythingElse = "Todas las demás"
    override val edit = "Editar"
    override val done = "Listo"
    override val editHint = "Toca una app para fijarla o desfijarla. Las apps fijadas quedan de primeras, en tu orden."
    override fun pin(app: String) = "Fijar $app"
    override fun unpin(app: String) = "Desfijar $app"
    override fun moveEarlier(app: String) = "Mover $app antes"
    override fun moveLater(app: String) = "Mover $app después"
    override val removeFromMostUsed = "Quitar de Más usadas"
    override fun removedFromMostUsed(app: String) = "Quitaste $app de Más usadas"
    override val undo = "Deshacer"
    override val searchApps = "Buscar apps"
    override fun noAppsMatch(query: String) = "Ninguna app coincide con “$query”"
    override val appsLoading = "Las apps de tu televisor aparecerán aquí una vez esté conectado."

    override val notConnected = "Nada conectado"
    override val onNow = "Ahora"
    override val typeHint = "Escribe aquí, se verá en el televisor mientras escribes"
    override val enter = "Enter"
    override val deleteChar = "Borrar"
    override val pasteFromPhone = "Pegar del celular"
    override val touchpadHint = "Arrastra para mover, toca para hacer clic, dos dedos para desplazarte"
    override val close = "Cerrar"

    override val openingOnTv = "Abriendo en tu TV"
    override val noLink = "No hay ningún enlace para abrir en el TV"
    override val pairFirst = "Primero elige tu TV y vuelve a compartir"
    override val bleKey = "Clave para encender por Bluetooth"
    override val bleKeyLabel = "16 dígitos hexadecimales"
    override val bleKeyHelp = "Avanzado. Con la clave que LG ThinQ usa para tu TV, Encender también funciona por Bluetooth, aunque el TV lleve horas apagado. Déjala vacía para desactivarlo."
    override val bleKeyInvalid = "Deben ser 16 dígitos hexadecimales (0-9, a-f)"
    override val controllers = "Tus controles"
    override val switchController = "Cambiar de control"
    override val addTv = "Agregar un TV"
    override val addPc = "Agregar un PC"
    override val defaultController = "Principal"
    override val makeDefault = "Usar como principal"
    override val remove = "Quitar"
    override val backToControllers = "Volver"
    override val pcWaiting = "Esperando al PC…"
    override val pcConnected = "Conectado"
    override val pcNoBluetooth = "Activa el Bluetooth para usar el PC"
    override val pcUnsupported = "Este teléfono no puede funcionar como teclado Bluetooth, así que no puede controlar un PC"
    override val pcBusy = "Otra app de este celular está funcionando como su teclado (¿otra app de control remoto?). Ciérrala para controlar este PC"
    override val fixKeys = "Arreglar teclado y mouse"
    override val adminBlocked = "Windows bloquea el modo fluido en ventanas de administrador."
    override val secureBlocked = "Windows bloquea el modo fluido en esta pantalla."
    override val useBluetooth = "Usar Bluetooth"
    override val fixKeysBody = "Si las teclas se repiten, salen caracteres equivocados o el puntero se comporta raro, el PC guardó una configuración vieja de este celular (otra app de control remoto puede causarlo).\n\nEn el PC, abre Configuración > Bluetooth y dispositivos y quita este celular. Luego toca Agregar un PC y empareja de nuevo."
    override val addPcSlow = "¿Sigue esperando? Deja esta pantalla abierta mientras el PC agrega el teléfono. Si el PC ya muestra este teléfono, quítalo ahí y agrégalo otra vez."
    override val pcPermission = "Controlled funciona como teclado y mouse Bluetooth para tu PC. Android te pedirá permiso para conectarse a dispositivos cercanos."
    override val addPcTitle = "Agregar un PC"
    override val addPcSteps = "Para PC con Windows (los atajos son de Windows).\n1. Toca Hacer visible este teléfono.\n2. En el PC: Configuración > Bluetooth y dispositivos > Agregar dispositivo > Bluetooth, y elige este teléfono.\n3. Confirma el código en ambos."
    override val makeVisible = "Hacer visible este teléfono"
    override val trackpadHint = "Arrastra para mover. Toca para hacer clic. Dos dedos: toca para clic derecho, arrastra para desplazarte. Toca, luego toca y arrastra para seleccionar."
    override val leftClick = "Clic izquierdo"
    override val rightClick = "Clic derecho"
    override val typeOnPc = "Escribe en el PC"
    override fun notTyped(chars: String) = "No se escribieron en el PC: $chars. Su teclado no los tiene."
    override val esc = "Esc"
    override val tab = "Tab"
    override val toolsWindows = "Ventanas"
    override val toolsTabs = "Pestañas"
    override val toolsEdit = "Editar"
    override val closeWindowName = "Cerrar ventana"
    override val minimize = "Minimizar"
    override val maximize = "Maximizar"
    override val snapLeft = "Izquierda"
    override val snapRight = "Derecha"
    override val previousTab = "Pestaña anterior"
    override val nextTab = "Pestaña siguiente"
    override val closeTab = "Cerrar"
    override val closeTabName = "Cerrar pestaña"
    override val newTab = "Nueva"
    override val reopenTab = "Reabrir"
    override val addressBar = "Dirección"
    override val goBack = "Atrás"
    override val reload = "Recargar"
    override val copy = "Copiar"
    override val paste = "Pegar"
    override val cut = "Cortar"
    override val selectAll = "Todo"
    override val redo = "Rehacer"
    override val find = "Buscar"
    override val deleteKey = "Supr"
    override val toolsKeys = "Teclas"
    override val toolsMedia = "Medios"
    override val toolsSystem = "Sistema"
    override val showDesktop = "Escritorio"
    override val files = "Archivos"
    override val fileExplorer = "Explorador de archivos"
    override val pcSettings = "Configuración"
    override val tasks = "Tareas"
    override val taskManager = "Administrador de tareas"
    override val runDialog = "Ejecutar"
    override val lockPc = "Bloquear"
    override val snip = "Recorte"
    override val snipName = "Captura de una zona"
    override val ctrlAltDel = "Ctrl+Alt+Supr"
    // Windows' own Spanish names for the display modes (Win+P).
    override val toolsScreen = "Pantalla"
    override val pcOnly = "Solo PC"
    override val pcOnlyName = "Solo pantalla de PC"
    override val duplicate = "Duplicar"
    override val extend = "Ampliar"
    override val secondOnly = "Segunda"
    override val secondOnlyName = "Solo segunda pantalla"
    override val start = "Inicio"
    override val nextWindow = "Otra"
    override val allWindows = "Todas"
    override val closeWindow = "Cerrar"
    override val switchWindow = "Cambiar"
    override val mute = "Silenciar"
    override val playPause = "Reproducir o pausar"
    override val previous = "Anterior"
    override val next = "Siguiente"
    override val renamePc = "Nombra este PC"
    override val pcName = "Nombre del PC"
    override val keyboardLayout = "Distribución del teclado"
    override val keyboardLayoutHint = "Elige la que usa este PC en Windows, para que lo que escribas salga bien."
    override val askLayout = "¿Qué teclado usa este PC?"
    override val layoutLatAm = "Español (Latinoamérica)"
    override val layoutSpain = "Español (España)"
    override val layoutUs = "Inglés (EE. UU.)"
    override val newVersion = "Hay una versión nueva"
    override val update = "Actualizar"
}

/** The texts of the phone's language: screens read this. */
val LocalStrings = staticCompositionLocalOf<Strings> { En }

/** The catalog for a language tag; anything that isn't Spanish is English. */
fun stringsOf(tag: String?): Strings = if (tag?.lowercase()?.startsWith("es") == true) Es else En
