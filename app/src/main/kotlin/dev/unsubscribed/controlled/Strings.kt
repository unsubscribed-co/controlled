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
    /** The countdown inside the pairing ring, for TalkBack: "52 seconds left". */
    fun secondsLeft(seconds: Int): String
    val cancel: String

    // Status line under the TV's name
    val connecting: String
    val turningOn: String
    val tvOff: String
    val tvOffNoPower: String
    val noWifi: String
    /** Turn on timed out: where LG keeps the setting that lets the TV wake from the phone. */
    val wakeHint: String
    fun nowOn(name: String): String
    val certificateChanged: String
    val forgetTv: String
    val privacy: String
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
    val pcPermission: String
    val addPcTitle: String
    val addPcSteps: String
    val makeVisible: String
    val trackpadHint: String
    val leftClick: String
    val rightClick: String
    val typeOnPc: String
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
    override val searching = "Looking for LG TVs on this Wi-Fi…"
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
    override fun secondsLeft(seconds: Int) = if (seconds == 1) "1 second left" else "$seconds seconds left"
    override val cancel = "Cancel"

    override val connecting = "Connecting…"
    override val turningOn = "Turning on…"
    override val tvOff = "TV is off. Tap to turn on"
    override val tvOffNoPower = "TV is off or out of reach. Tap to retry"
    override val noWifi = "No Wi-Fi. Connect to your home Wi-Fi"
    override val wakeHint = "The TV didn't wake. On the TV, turn on Settings > General > Devices > Mobile TV On > Turn on via Wi-Fi"
    override fun nowOn(name: String) = "On now: $name"
    override val certificateChanged = "This TV's security certificate changed. If you replaced or reset the TV, forget it and pair again."
    override val forgetTv = "Forget this TV"
    override val privacy = "Privacy policy"
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
    override val pcPermission = "Controlled works as a Bluetooth keyboard and mouse for your PC. Android will ask to let it connect to nearby devices."
    override val addPcTitle = "Add a PC"
    override val addPcSteps = "1. Tap Make this phone visible.\n2. On the PC: Settings > Bluetooth & devices > Add device > Bluetooth, and pick this phone.\n3. Confirm the code on both."
    override val makeVisible = "Make this phone visible"
    override val trackpadHint = "Drag to move. Tap to click. Two fingers: tap to right-click, drag to scroll. Tap, then touch and drag to select."
    override val leftClick = "Left click"
    override val rightClick = "Right click"
    override val typeOnPc = "Type on the PC"
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
    override val searching = "Buscando televisores LG en este Wi-Fi…"
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
    override fun secondsLeft(seconds: Int) = if (seconds == 1) "Queda 1 segundo" else "Quedan $seconds segundos"
    override val cancel = "Cancelar"

    override val connecting = "Conectando…"
    override val turningOn = "Encendiendo…"
    override val tvOff = "El TV está apagado. Toca para encender"
    override val tvOffNoPower = "El TV está apagado o fuera de alcance. Toca para reintentar"
    override val noWifi = "Sin Wi-Fi. Conéctate al Wi-Fi de tu casa"
    override val wakeHint = "El TV no encendió. En el TV, activa Configuración > General > Dispositivos > Encender TV con móvil > Encender por Wi-Fi"
    override fun nowOn(name: String) = "Ahora: $name"
    override val certificateChanged = "El certificado de seguridad de este televisor cambió. Si reemplazaste o restableciste el TV, olvídalo y vincúlalo de nuevo."
    override val forgetTv = "Olvidar este TV"
    override val privacy = "Política de privacidad"
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
    override val pcPermission = "Controlled funciona como teclado y mouse Bluetooth para tu PC. Android te pedirá permiso para conectarse a dispositivos cercanos."
    override val addPcTitle = "Agregar un PC"
    override val addPcSteps = "1. Toca Hacer visible este teléfono.\n2. En el PC: Configuración > Bluetooth y dispositivos > Agregar dispositivo > Bluetooth, y elige este teléfono.\n3. Confirma el código en ambos."
    override val makeVisible = "Hacer visible este teléfono"
    override val trackpadHint = "Arrastra para mover. Toca para hacer clic. Dos dedos: toca para clic derecho, arrastra para desplazarte. Toca, luego toca y arrastra para seleccionar."
    override val leftClick = "Clic izquierdo"
    override val rightClick = "Clic derecho"
    override val typeOnPc = "Escribe en el PC"
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
    override val newVersion = "Hay una versión nueva"
    override val update = "Actualizar"
}

/** The texts of the phone's language: screens read this. */
val LocalStrings = staticCompositionLocalOf<Strings> { En }

/** The catalog for a language tag; anything that isn't Spanish is English. */
fun stringsOf(tag: String?): Strings = if (tag?.lowercase()?.startsWith("es") == true) Es else En
