# Letterboxd List Suggester — estensione

Aggiunge un bottone **"Suggest similar films"** alle pagine lista di Letterboxd.
I risultati compaiono in un pannello **sopra la griglia**, così sono visibili
senza scorrere anche su liste da centinaia di film.

## Installazione (non impacchettata)

1. Apri `chrome://extensions/`
2. Attiva **Modalità sviluppatore** in alto a destra
3. **Carica estensione non pacchettizzata** → seleziona questa cartella `extension/`
4. Vai su una qualsiasi lista Letterboxd

Su Firefox: `about:debugging` → *Questo Firefox* → *Carica componente aggiuntivo
temporaneo* → scegli `manifest.json`.

## Come funziona

Tutto gira nel browser dell'utente, dentro la scheda Letterboxd. Le richieste
sono **same-origin**: niente CORS, niente server, e portano con sé la sessione
dell'utente — per questo funziona anche sulle liste private.

1. Legge la lista completa, seguendo la paginazione (a prescindere da quale
   pagina stai guardando)
2. Se attivo, legge i film già visti da `/username/films/`
3. Scarica `/film/{slug}/similar/` per ogni film della lista, max 8 richieste in
   parallelo
4. Conta le ricorrenze pesate per posizione: un match in cima alla lista dei
   simili vale il doppio di uno in fondo
5. Esclude i film già presenti nella lista

## Film già visti: marcati, non nascosti

Questo è uno strumento per **curare una lista**, e un film che hai visto e amato
è esattamente ciò che vuoi aggiungerci. Nasconderlo di default sarebbe la scelta
sbagliata: Spotify non nasconde i brani che hai già ascoltato, li propone perché
stanno bene nella playlist.

Quindi i suggerimenti che hai già visto ricevono un'etichetta **SEEN** e restano
in classifica. Nel pannello c'è un filtro `Hide films I have seen (N)` che li
toglie **istantaneamente**, senza rilanciare nulla, perché i dati sono già in
memoria.

Il filtro pesca da un bacino più ampio dei risultati mostrati (`POOL_FACTOR` in
`recommend.js`), così nascondendo i visti la griglia resta piena invece di
assottigliarsi: i film successivi vengono promossi al loro posto.

Se non vuoi proprio la funzione, togli la spunta nel popup: niente etichette,
niente filtro, e nessuna richiesta a `/username/films/`.

Le locandine si risolvono **solo quando la card entra nel viewport**
(`IntersectionObserver`), perché ognuna costa una richiesta alla pagina film.

## Cache

| Dato | Durata | Perché |
|---|---|---|
| Film simili | 24 ore | Cambiano lentamente |
| Locandine | 30 giorni | Praticamente mai |
| Film visti | 12 ore | Cambiano quando logghi un film |
| Contenuto lista | 1 ora | Può essere modificata |

Si svuota dal popup dell'estensione.

## Struttura

```
extension/
├─ manifest.json
├─ src/
│  ├─ store.js       cache + impostazioni su chrome.storage
│  ├─ letterboxd.js  fetch e parsing — TUTTI i selettori stanno qui
│  ├─ recommend.js   conteggio pesato e classifica
│  ├─ panel.js       UI dei risultati, in Shadow DOM
│  └─ content.js     iniezione del bottone e orchestrazione
└─ popup/            impostazioni
```

**Se Letterboxd cambia il markup**, l'unico file da toccare è `letterboxd.js`:
i selettori sono raccolti in cima. Il pannello non dipende dal loro CSS perché
vive in uno shadow root con stili propri.

## Cosa resta da verificare

`detectUsername()` in `letterboxd.js` prova a leggere lo username dal nav
dell'account, ma **non è stato possibile verificarlo senza una sessione
loggata**. Se le etichette SEEN non compaiono, inserisci lo username nel popup:
quello è il percorso garantito. Una volta appurato il selettore giusto, è una
riga da correggere.

## Rapporto con la versione Java

La logica di ranking è un porting fedele di `RecommendationService`: sugli stessi
dati produce classifica e punteggi identici. L'app Java resta utile come
strumento locale e implementazione di riferimento.
