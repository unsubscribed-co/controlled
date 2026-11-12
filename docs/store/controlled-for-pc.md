# Microsoft Store listing: Controlled for PC (draft, for Juan's review)

Partner Center product **9N08NXM529FH** ("Controlled for PC", publisher "Unsubscribed!"). The name reservation lapses if
nothing is submitted by about 2027-02-09. Juan submits (his "ship it"); nothing here is published.

## Package
- `pc/msix/pack.ps1 <version>` builds `pc/target/ControlledForPC-<version>.msix`. The Store upload is unsigned (Microsoft
  signs it).
- Tested 2026-11-09 as a locally signed install on Juan's desktop. It installed with the reserved identity, raised no
  firewall prompt, and the phone connected over Wi-Fi.
- Not yet tested: starting with Windows after a sign-in.
- Capabilities to justify in the submission:
  - `runFullTrust`: it's a desktop app that turns the phone's input into mouse and keyboard input with SendInput.
  - `privateNetworkClientServer`: the phone connects to it on the home network.
- No firewall rules since 1.0.5: the phone and PC meet over Bluetooth and the PC dials the phone.

## Properties
- Category: **Utilities & tools**. Price: **free**, in all markets.
- Privacy policy URL: https://unsubscribed.dev/controlled/privacy/ (it has a "Controlled for PC" section; deploy it
  before submitting).
- Website: https://unsubscribed.dev/controlled/
- Support: https://github.com/unsubscribed-co/controlled/issues

## Age rating questionnaire (IARC)
- No violence, sexual content, gambling or purchases.
- No user-generated content shared with others, and no chat.
- Doesn't share location.
- Expected rating: everyone.

## Listing (English)
**Short description:** Smooth mode for the Controlled phone app: your phone as this PC's mouse and keyboard over Wi-Fi.

**Description:**
Controlled turns your Android phone into a remote for your TV and a keyboard and trackpad for your PC. It works over
Bluetooth with nothing installed. Controlled for PC adds smooth mode:

- A smooth pointer: your phone sends the mouse over Wi-Fi, without Bluetooth's stutter. It sets itself up through the
  Bluetooth pairing: no network settings, no firewall rules, on any Wi-Fi or your phone's hotspot.
- Paste long text on your phone and it arrives whole and exact; what you had copied on the PC comes back.
- Types any character, whatever the PC's keyboard layout.
- Private by design:
  - it pairs once with the same 6-digit code on both screens;
  - everything is encrypted between your phone and your PC, and nothing goes through a server;
  - no account, no server, no ads, no analytics.
- Always in sight: the tray icon lights up while a phone has control. Its window lists your allowed phones (remove any
  in one click), and Pause stops smooth mode at once.
- From Unsubscribed!, software on a fair deal: the code is open (AGPL-3.0) for anyone to use, check, improve or run
  themselves, and a service we run for you costs what it costs to run plus a fee shown up front. Controlled runs only
  between your own devices and costs us nothing to run, so it's free: no ads, no subscription, no account.

Needs the Controlled app on Android (Google Play). Bluetooth stays as the backup: on the sign-in screen and in
administrator windows, Windows only accepts the Bluetooth keyboard.

**Features (up to 20, short):**
- Smooth pointer over Wi-Fi
- Any character, any keyboard layout
- Pair once with a 6-digit code
- Sets itself up over Bluetooth, no firewall
- Long text pasted whole and exact
- Encrypted, no server
- Tray icon shows who has control
- Pause and remove phones anytime
- No account, no ads, no analytics
- Free and open source

## Listing (Spanish, es-419)
**Descripción corta:** Modo fluido para la app Controlled: tu celular como mouse y teclado de este PC por Wi-Fi.

**Descripción:**
Controlled convierte tu celular Android en el control de tu TV y en el teclado y trackpad de tu PC. Funciona por
Bluetooth sin instalar nada. Controlled for PC agrega el modo fluido:

- Un puntero fluido: tu celular envía el mouse por Wi-Fi, sin los saltos del Bluetooth. Se configura solo con el
  emparejamiento Bluetooth: sin ajustes de red ni firewall, en cualquier Wi-Fi o en el hotspot de tu celular.
- Pega un texto largo en tu celular y llega completo y exacto; lo que tenías copiado en el PC vuelve.
- Escribe cualquier carácter, sin importar la distribución del teclado del PC.
- Privado por diseño:
  - se vincula una sola vez con el mismo código de 6 dígitos en ambas pantallas;
  - todo va cifrado entre tu celular y tu PC, sin pasar por ningún servidor;
  - sin cuentas, sin servidores, sin anuncios ni analíticas.
- Siempre a la vista: el ícono de la bandeja se enciende mientras un celular tiene el control. Su ventana lista los
  celulares permitidos (quita cualquiera con un clic), y Pausar detiene el modo fluido de inmediato.
- De Unsubscribed!, software con un trato justo: el código es abierto (AGPL-3.0) para que cualquiera lo use, lo
  revise, lo mejore o lo corra por su cuenta, y un servicio que corremos por ti cuesta lo que cuesta correrlo más una
  tarifa que se muestra antes. Controlled funciona solo entre tus propios equipos y correrlo no nos cuesta nada, así
  que es gratis: sin anuncios, sin suscripción, sin cuenta.

Necesita la app Controlled en Android (Google Play). El Bluetooth queda de respaldo: en la pantalla de inicio de sesión
y en las ventanas de administrador, Windows solo acepta el teclado Bluetooth.

**Características:**
- Puntero fluido por Wi-Fi
- Cualquier carácter, cualquier teclado
- Vinculación única con código de 6 dígitos
- Se configura solo por Bluetooth, sin firewall
- Textos largos pegados completos y exactos
- Cifrado, sin servidores
- El ícono muestra quién tiene el control
- Pausa y quita celulares cuando quieras
- Sin cuentas, anuncios ni analíticas
- Gratis y de código abierto

## Screenshots needed (desktop, at least 1366×768)
1. The window connected ("Connected to POCO X7 Pro") with the tray icon lit.
2. The pairing request with its code next to the phone showing the same code.
3. Your phones list.

A captured window is 442×592: each one goes on a 1920×1080 canvas, made by a script like `docs/brand/screens.py`.

## Before submitting (checklist)
- [ ] Juan's OK on the texts above.
- [ ] Privacy page with the PC section deployed, with its effective date updated.
- [ ] Starting with Windows verified after a sign-out and sign-in.
- [ ] Dark mode looked at.
- [ ] Screenshots made.
- [ ] Phone app with smooth mode released to Play at the same time, so the Store's "Needs Controlled" is true.
