# AmpyLink — Canon EOS R6 Mark II ▸ KODAK Mini 2 Retro (stile AmpyRaw)

App Android con lo stesso look cyberpunk di AmpyRaw (palette neon ciano/magenta/giallo, mono bold, pulsanti smussati, slider a diamante). Fa da ponte fra la fotocamera e la stampante:

| | |
|---|---|
| **EOS Wi-Fi** | Come Canon Camera Connect: ricerca **UPnP/SSDP**, poi **PTP/IP** (porta 15740). Invia nome del telefono e un **GUID persistente**, così la R6 II chiede una sola volta di confermare l'associazione (mentre il prompt è a schermo l'app ritenta). Usa la modalità remote/event EOS (0x9114/0x9115/0x9116), risponde ai ProbeRequest sul canale eventi e invia keep-alive. In alternativa **CCAPI** (HTTP 8080) se l'hai attivato. |
| **Galleria** | Raggruppata per giorno (OGGI / IERI / data), coppie **RAW+JPG** unite in un'unica miniatura con badge, filtri TUTTE/JPG/RAW, ordinamento, griglia 2–5 colonne, selezione multipla (pressione lunga) per stampa o salvataggio in serie. Scheda TELEFONO per foto e RAW già sul telefono. |
| **Viewer + EXIF** | Anteprima con zoom e pan, swipe tra gli scatti, istogramma RGB+L. Mostra fotocamera, obiettivo, data e ora, tempo, diaframma, ISO, focale, compensazione EV, modalità, esposimetro, bilanciamento del bianco, flash, pixel e seriale. L'EXIF si legge dai primi byte del file (GetPartialObject): per i CR3 dai box CMT1/CMT2. Anche l'anteprima dei CR3 senza JPG si prende dal file (PRVW 1620×1080), quindi non serve scaricare 25 MB. |
| **Stampa diretta** | Bluetooth SPP diretto verso la **KODAK Mini 2 Retro (P210R, 4PASS)**, senza passare dall'app KODAK. Scheda 640×1024 (2.1×3.4") con cornici FULL / BORDER / RETRO / INSTANT / FILM / NEON, data impressa stile compatta anni '90, compensazione dye-sub, copie 1–9. La foto si riposiziona trascinandola e si zooma con due dita. Durante la stampa vedi l'avanzamento del trasferimento e le passate Y/M/C/laminato. Stampa in serie per le selezioni multiple. L'invio all'app KODAK resta come alternativa. |
| **Editor RAW esterno** | Elenca gli editor installati (Lightroom, Snapseed, AmpyRaw, Photoshop Express, PhotoDirector…) mettendo in cima quelli RAW, ricorda l'ultimo scelto e passa il CR3 o il JPG originale tramite un provider in sola lettura. |
| **Editor in-app + cornice** | Editor GPU di AmpyRaw integrato (CR3 reale tramite il decoder CRX portato da RawTherapee, ADJ/LENS/CURVE/LOOKS, prima/dopo, istogramma). Il pulsante **FRAME** sovrappone in tempo reale la scheda KODAK con la cornice scelta: mostra il ritaglio esatto che verrà stampato e la cornice disegnata con lo stesso codice della stampa. Poi PRINT o SAVE. |

## Protocollo stampante (ricostruito dall'app KODAK Photo Printer 5.1.4)
Il formato dei pacchetti, i comandi e il flusso sono ricavati dal codice Java dell'app ufficiale (`com.prinics.ppvp` / `z3.h`), riscritti da zero. Il flusso completo è descritto in testa a `KodakBt.java`.

Ogni pacchetto è fatto così: header `[cmd, p1, p2, p3, lunghezza u32 BE]` + dati. Il flusso è hello `0x02`, poi "pronta" `0x04/1`, annuncio del lavoro `0x05` (copie + dimensione JPEG), richieste dati `0x08` a cui si risponde con blocchi `0x09`, stato di stampa `0x04/3` (passata in `data[7]`), fine `0x04/6` che si chiude con `0x01`. Gli errori arrivano come `0x04/4` con un codice. Il JPEG viene riempito con zeri fino ad almeno 33 792 byte.

**Limiti noti**
* La libreria nativa `libprinics_ip` (ridimensionamento/rotazione per la stampante) manca dall'APK caricato, perché è uno split. AmpyLink invia un JPEG baseline 640×1024 in verticale, come la tela "2inch" dell'app KODAK. Se la prima stampa esce ruotata o scalata male, usa "APRI NELL'APP KODAK" e segnalamelo: si corregge in `PrintSheet.jpeg()`.
* I testi dei codici di errore della stampante sono interpretati in base ai dialoghi dell'app ufficiale.
* Il protocollo è stato provato contro una stampante e una fotocamera simulate, non contro i dispositivi veri.

## Build
* **Gradle**: `gradle :app:assembleDebug` (c'è anche il workflow GitHub Actions in `.github/workflows/build.yml`).
* **Senza Gradle**: `SDK_JAR=…/android.jar BT=…/build-tools/34.x ./build-apk.sh`, che usa aapt2, javac, d8 e apksigner.
* **Senza D8** (dx al suo posto): passa anche `DEXER="java -jar dx.jar" ASM_JAR=asm.jar`. `tools/Desugar.java` converte le lambda in classi normali, perché Android non ha `LambdaMetafactory`. Era la causa del crash all'avvio della 1.0.0.

Licenza GPL v3 (deriva da AmpyRaw / RawTherapee).

### AmpyLink Fusion 1.8.0

Questa variante riunisce Canon EOS, galleria, editor, stampa Kodak e AmpyOrb Core
in un APK Android 11+ con un identificativo dedicato, `com.ampylink.fusion`.
Dalla schermata iniziale si sceglie il modulo fotografico oppure Orb.

Per condividere uno schermo in LAN, aprire Orb sul telefono host e premere
**Share this screen**. Autorizzare la cattura Android. Il servizio di
accessibilità serve solo se si desiderano abilitare anche i gesti remoti.
Sul controller scegliere **Cerca dispositivi Orb** oppure digitare l’IPv4,
inserire il segreto temporaneo mostrato sull’host e premere **Connect to peer**.
Per controllare un secondo host, scegliere **Apri un altro dispositivo**.
Il tasto Indietro riporta alla sessione precedente. Ogni host accetta un
controller. La notifica dell’host permette di interrompere la condivisione.

Il protocollo integrato proviene da LiveSync 0.2 (JPEG, Android e client desktop
compatibili con il protocollo v1). La ricerca automatica elenca gli host Fusion
che annunciano il servizio. I client LiveSync precedenti possono usare l’IPv4.

### Notifiche e sessioni in background (1.8.1)

Su Android, il servizio in primo piano mostra gli host collegati e permette
di interrompere le sessioni dalla notifica. Home lascia attiva la connessione.
La notifica di condivisione sul dispositivo host mostra il peer autenticato.
Su Windows x64 estrarre tutto lo ZIP e aprire AmpyLink-Fusion.exe. Il runtime
Java è incluso. La X nasconde la finestra nel tray. Dal menu dell’icona si
può riaprire la finestra, disconnettere il peer o uscire dal programma.
Per ricompilare su Windows usare JDK 17, Go e desktop/build.ps1 con
-RuntimePath diretto a un runtime Java 17 Windows x64.
