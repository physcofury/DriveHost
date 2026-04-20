$s = Get-Content "C:\Users\calvi\AppData\Local\playit_gg\playit.toml" | ForEach-Object {
    if ($_ -match 'secret_key\s*=\s*"(.+)"') { $Matches[1] }
}

$createBody = '{"name":"drivehost-mc","tunnel_type":"minecraft-java","port_type":"tcp","port_count":1,"origin":{"type":"agent","data":{"agent_id":"5a70000c-f046-4fcc-a22d-5a48b840148e","local_ip":"127.0.0.1","local_port":25565}},"enabled":true,"alloc":null,"firewall_id":null,"proxy_protocol":null}'
Write-Host "=== CREATE ==="
$r1 = curl.exe -s -X POST "https://api.playit.gg/tunnels/create" -H "Authorization: agent-key $s" -H "Content-Type: application/json" --data-raw $createBody
Write-Host $r1

Write-Host "=== LIST ==="
$r2 = curl.exe -s -X POST "https://api.playit.gg/tunnels/list" -H "Authorization: agent-key $s" -H "Content-Type: application/json" --data-raw '{}'
Write-Host $r2

$body = @{
    name         = "drivehost-mc"
    tunnel_type  = "minecraft-java"
    port_type    = "tcp"
    port_count   = 1
    origin       = @{
        type = "agent"
        data = @{
            agent_id   = "5a70000c-f046-4fcc-a22d-5a48b840148e"
            local_ip   = "127.0.0.1"
            local_port = 25565
        }
    }
    enabled      = $true
    alloc        = $null
    firewall_id  = $null
    proxy_protocol = $null
} | ConvertTo-Json -Depth 5

Write-Host "Secret (first 8): $($s.Substring(0,8))"
Write-Host "Body: $body"

$result = curl.exe -s -X POST "https://api.playit.gg/tunnels/create" `
    -H "Authorization: agent-key $s" `
    -H "Content-Type: application/json" `
    -d $body

Write-Host "Result: $result"

# Also list
$list = curl.exe -s -X POST "https://api.playit.gg/tunnels/list" `
    -H "Authorization: agent-key $s" `
    -H "Content-Type: application/json" `
    -d '{"tunnel_id":null,"agent_id":null}'
Write-Host "List: $list"
