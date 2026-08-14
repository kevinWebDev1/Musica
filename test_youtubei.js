const https = require('https');
const videoId = "inEu2qQuGZ8";
const postData = JSON.stringify({
    context: {
        client: {
            clientName: "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion: "2.0"
        },
        thirdParty: {
            embedUrl: "https://www.youtube.com/watch?v=" + videoId
        }
    },
    videoId: videoId
});
const options = {
    hostname: 'youtubei.googleapis.com',
    path: '/youtubei/v1/player',
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Content-Length': postData.length
    }
};
const req = https.request(options, (res) => {
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        if (data.includes('hlsManifestUrl')) {
            console.log("Has hlsManifestUrl:", data.split('hlsManifestUrl":"')[1].split('"')[0]);
        }
        if (data.includes('dashManifestUrl')) {
            console.log("Has dashManifestUrl:", data.split('dashManifestUrl":"')[1].split('"')[0]);
        }
        console.log("Found formats count:", (data.match(/"itag":/g) || []).length);
    });
});
req.write(postData);
req.end();
