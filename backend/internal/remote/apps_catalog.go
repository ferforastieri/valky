package remote

// knownAppIDs is the catalogue of Samsung Tizen application IDs the backend
// probes against the TV REST API. Newer firmwares no longer answer the
// "installed apps" WebSocket event, so the only way to discover apps is to ask
// the TV whether it has each known application: the REST endpoint returns the
// application name for installed apps and 404 otherwise. The list covers the
// most common apps worldwide (and Brazilian ones), with the alternate IDs used
// across Tizen releases.
var knownAppIDs = []string{
	// Streaming and video
	"11101200001", "3201907018807", // Netflix
	"111299001912",                   // YouTube
	"3201707014489",                  // YouTube TV
	"3201611010983",                  // YouTube Kids
	"3201512006785", "3201910019365", // Prime Video
	"3201901017640", "3202009021709", "3202204027038", // Disney+
	"3201601007230", "3202301029760", // HBO Max / Max
	"3201706012478", "3202004020575", // HBO GO
	"3201601007625",                  // Hulu
	"3201512006963",                  // Plex
	"3201606009872",                  // Emby
	"3201511006428",                  // Rakuten TV
	"3201602007756",                  // Red Bull TV
	"3201808016802",                  // Pluto TV
	"3201803015944",                  // Discovery+
	"3202208028071",                  // SkyShowtime
	"3201710014981", "3202110025305", // Paramount+
	"3202006020991",                  // Peacock TV
	"3202008021577",                  // TikTok
	"3202203026841",                  // Twitch
	"3202203026799",                  // Xbox
	"3201702011851",                  // Steam Link
	"3202008021439",                  // Google Meet
	"3202203026819",                  // Amazon Luna
	"3202503039231",                  // Copilot
	"3202004020674",                  // Google Assistant
	"3202004020626",                  // Amazon Alexa
	"3202009021877",                  // Security Center
	"3201612011418",                  // McAfee Security
	"3201908019041",                  // Apple Music
	"3201807016597",                  // Apple TV
	"3201710014874",                  // Amazon Music
	"3201606009684",                  // Spotify
	"3201805016367",                  // TIDAL
	"3201608010191",                  // Deezer
	"121299000101",                   // TuneIn
	"3201711015226",                  // Radio UK
	"3202012022468",                  // Radio WOW
	"111399002220",                   // SiriusXM
	"3201412000690",                  // Bloomberg
	"3201602007865",                  // BBC News
	"3202003020365",                  // BBC Sounds
	"3201601007670",                  // BBC iPlayer
	"3201909019175",                  // BritBox
	"3201806016390",                  // DAZN
	"3201708014618",                  // ESPN
	"3201603008210",                  // MLB
	"3201703012065",                  // Love Nature 4K
	"3201902017887",                  // Shudder
	"3201504001965",                  // Tubi
	"3201506002963", "3201903018045", // Showmax
	"3201910019457",                                   // Kidoodle.TV
	"3201705012375",                                   // Blackdove Video Art
	"3202201026324",                                   // Nifty Gateway
	"3201804016109",                                   // DStv
	"3201710015067",                                   // Universal Guide
	"3201710015037",                                   // Gallery
	"3202405035480",                                   // Samsung TV Plus
	"3201710015016", "3201910019378", "3202311033054", // SmartThings

	// Browsers and IPTV
	"org.tizen.browser", "3201907018784", "3202010022079", // Internet
	"3201812017585", // IPTV Smarters
	"3202009021808", // IPTV Smarters Pro
	"3202102022877", // SmartOne IPTV
	"3201906018693", // Focus Sat
	"3201909019271", // PrivacyChoices

	// Brazil and Latin America
	"3201908019022", // globoplay
	"3201604009182", // Telecine
	"3201907018786", // DIRECTV GO
	"111299001957",  // Movistar TV Chile
	"3201712015454", // Estadio TNT Sports
	"3201905018499", // 13 Now
	"3201706014259", // Mi Perro Chocolo
	"111399000741",  // The Weather Network
	"111399002034",  // RaiPlay

	// Europe
	"3201603008746",                  // NOW TV
	"3202011022131",                  // NOW PlayTV
	"111299000769",                   // VOYO.RO
	"3201611011005",                  // AntenaPlay.ro
	"3201703012079",                  // Eurosport Player
	"3201608010385",                  // EduPedia
	"3201710014866",                  // Orange TV Go
	"3201506003175",                  // Pathe Thuis
	"3201803015869",                  // Canaal Digitaal
	"3202012022421",                  // NL Ziet
	"3201706014250",                  // NPO
	"3201901017768",                  // Kijk
	"3201810017074",                  // Videoland
	"3201606009910", "3202010022094", // CANAL+
	"3201611011210", // Molotov
	"3202103023185", // OQEE by Free
	"3201910019420", // B.tv
	"3201704012212", // RMC Sport
	"3202008021462", // Telefoot
	"3201810017091", // Playzer
	"3201703012029", // OCS
	"3202011022316", // ARTE
	"3202007021336", // Benshi
	"3202103023232", // france.tv
	"3202209028524", // TF1+
	"3202207027908", // M6+
	"3202303030351", // RMC BFM Play
	"3202311033030", // Pathe Home
	"3202105023867", // Orange TV
	"3201506003716", // Mako
	"3201812017600", // Reshet13
	"3201803015997", // Kan11
	"3201412000679", // ARD Mediathek
	"3201705012365", // ZDF Mediathek
	"3201810017070", // HD+
	"3201908018988", // RTL+
	"3201907018746", // MagentaTV
	"3201502001386", // Joyn
	"3201906018642", // RTL XL
	"11111300404",   // Viaplay
	"3201803015963", // KPN iTV
	"3202205027432", // DigiOnline

	// Asia and Oceania
	"3202006020934",                  // iQIYI
	"3201910019513",                  // iWantTFC
	"3201807016555",                  // Viu
	"3201806016432",                  // UKTV Play
	"111299002148",                   // All 4
	"111012010001",                   // VUDU
	"11091000000",                    // Facebook Watch
	"121299000089",                   // ITV Hub
	"121299000612",                   // My5
	"3201806016381",                  // hayu
	"3201803015934",                  // 7plus
	"3201607010031",                  // 9Now
	"3201910019354",                  // Kayo Sports
	"3201812017479", "3201704012147", // ABC iview / 10 play
	"3202204027037", // The Austria Channel
	"11101000407",   // Telstra TV Box Office
	"3201606009798", // Stan
	"3202010022098", // BINGE
	"3201910019449", // Foxtel
	"3201510005981", // SBS On Demand
	"3201506003486", // CTV
	"3201506003488", // Crave
	"3201811017337", // AMC
	"3202008021516", // TMobile TV
}
