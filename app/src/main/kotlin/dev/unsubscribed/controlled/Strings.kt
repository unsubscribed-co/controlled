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
    val cancel: String

    // Remote
    val connecting: String
    val turningOn: String
    val off: String
    val reconnect: String
    val certificateChanged: String
    val forgetTv: String
    val powerOn: String
    val powerOff: String
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
    val mute: String
    fun volume(level: Int): String
    val channelUp: String
    val channelDown: String
    val channel: String
    val play: String
    val pause: String
    val keyboard: String
    val touchpad: String

    // Keyboard and touchpad sheets
    val typeHint: String
    val send: String
    val enter: String
    val deleteChar: String
    val touchpadHint: String
    val close: String

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
    override val addressHint = "e.g. 192.168.1.12 (TV Settings > Network)"
    override val connect = "Connect"
    override val unreachable = "Couldn't reach the TV at that address."
    override val rejected = "The TV said no. Try again and choose Accept on the TV."
    override val noAnswer = "No answer from the TV. Try again and choose Accept on the TV."
    override fun pairingTitle(tv: String) = "Accept on $tv"
    override val pairingBody = "Your TV is asking whether to let this phone control it. Choose Accept with the TV's remote. You only do this once."
    override val cancel = "Cancel"

    override val connecting = "Connecting…"
    override val turningOn = "Turning on…"
    override val off = "The TV is off or out of reach."
    override val reconnect = "Reconnect"
    override val certificateChanged = "This TV's security certificate changed. If you replaced or reset the TV, forget it and pair again."
    override val forgetTv = "Forget this TV"
    override val powerOn = "Turn on"
    override val powerOff = "Turn off"
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
    override val mute = "Mute"
    override fun volume(level: Int) = "Volume $level"
    override val channelUp = "Next channel"
    override val channelDown = "Previous channel"
    override val channel = "CH"
    override val play = "Play"
    override val pause = "Pause"
    override val keyboard = "Type"
    override val touchpad = "Touchpad"

    override val typeHint = "Type here, it appears on the TV"
    override val send = "Send"
    override val enter = "Enter"
    override val deleteChar = "Delete"
    override val touchpadHint = "Drag to move, tap to click, two fingers to scroll"
    override val close = "Close"

    override val newVersion = "A new version is available"
    override val update = "Update"
}

object Es : Strings {
    override val permissionTitle = "Busca tu televisor"
    override val permissionBody = "Controlled se comunica con tu televisor por el Wi-Fi de tu casa. Android te pedirá permiso para buscar y conectarse a dispositivos en tu red local. Nada sale de tu casa."
    override val permissionAllow = "Continuar"
    override val permissionDenied = "Sin este permiso Controlled no puede conectarse a tu televisor."
    override val openSettings = "Abrir ajustes"

    override val findTitle = "Elige tu televisor"
    override val searching = "Buscando televisores LG en este Wi-Fi…"
    override val noneFound = "No encontramos ningún televisor. Revisa que esté encendido y en el mismo Wi-Fi, o escribe su dirección."
    override val searchAgain = "Buscar de nuevo"
    override val typeAddress = "Dirección del televisor"
    override val addressHint = "ej. 192.168.1.12 (Configuración > Red)"
    override val connect = "Conectar"
    override val unreachable = "No se pudo conectar al televisor en esa dirección."
    override val rejected = "El televisor dijo que no. Intenta de nuevo y elige Aceptar en el televisor."
    override val noAnswer = "El televisor no respondió. Intenta de nuevo y elige Aceptar en el televisor."
    override fun pairingTitle(tv: String) = "Acepta en $tv"
    override val pairingBody = "Tu televisor te está preguntando si permites que este celular lo controle. Elige Aceptar con el control del televisor. Solo tienes que hacer esto una vez."
    override val cancel = "Cancelar"

    override val connecting = "Conectando…"
    override val turningOn = "Encendiendo…"
    override val off = "El televisor está apagado o fuera de alcance."
    override val reconnect = "Reconectar"
    override val certificateChanged = "El certificado de seguridad de este televisor cambió. Si lo cambiaste o lo restableciste, olvídalo y vincúlalo de nuevo."
    override val forgetTv = "Olvidar este televisor"
    override val powerOn = "Encender"
    override val powerOff = "Apagar"
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
    override val mute = "Silenciar"
    override fun volume(level: Int) = "Volumen $level"
    override val channelUp = "Canal siguiente"
    override val channelDown = "Canal anterior"
    override val channel = "CH"
    override val play = "Reproducir"
    override val pause = "Pausar"
    override val keyboard = "Escribir"
    override val touchpad = "Touchpad"

    override val typeHint = "Escribe aquí, aparece en el televisor"
    override val send = "Enviar"
    override val enter = "Enter"
    override val deleteChar = "Borrar"
    override val touchpadHint = "Arrastra para mover, toca para hacer clic, dos dedos para desplazarte"
    override val close = "Cerrar"

    override val newVersion = "Hay una versión nueva"
    override val update = "Actualizar"
}

/** The texts of the phone's language: screens read this. */
val LocalStrings = staticCompositionLocalOf<Strings> { En }

/** The catalog for a language tag; anything that isn't Spanish is English. */
fun stringsOf(tag: String?): Strings = if (tag?.lowercase()?.startsWith("es") == true) Es else En
