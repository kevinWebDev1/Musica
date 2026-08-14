$body = '{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20230508.01.00"}},"browseId":"UCqcbM47XoV6vP16pD2N2f_Q"}'
$response = Invoke-RestMethod -Uri "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false" -Method Post -Body $body -ContentType "application/json"
$response.header | ConvertTo-Json -Depth 10 > header.json
