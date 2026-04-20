@echo off
set S=7476d1b4e06494c4c95b79ea598ac3d081681867a7d3f2bdd672b3aab28f9aee

echo --- no data ---
curl -s "https://api.playit.gg/tunnels/create" -X POST -H "Authorization: agent-key %S%" -H "Content-Type: application/json" -d "{\"tunnel_type\":\"minecraft-java\",\"port_type\":\"tcp\",\"port_count\":1,\"origin\":{\"type\":\"agent\"},\"enabled\":true}"
echo.

echo --- managed ---
curl -s "https://api.playit.gg/tunnels/create" -X POST -H "Authorization: agent-key %S%" -H "Content-Type: application/json" -d "{\"tunnel_type\":\"minecraft-java\",\"port_type\":\"tcp\",\"port_count\":1,\"origin\":{\"type\":\"managed\",\"data\":{\"agent_id\":\"5a70000c-f046-4fcc-a22d-5a48b840148e\",\"agent_name\":\"drivehost\"}},\"enabled\":true}"
echo.
