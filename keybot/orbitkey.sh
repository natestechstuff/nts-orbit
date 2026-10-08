#!/bin/sh
# orbitkey: make / check / list NTS Orbit premium keys with the bot's signing key.
#   sudo orbitkey gen --tier PRO --note "sold to ..."     sudo orbitkey list     orbitkey verify ORBIT-...
exec sudo -u orbitkeybot env PYTHONPATH=/opt/orbit-keybot ORBIT_KEYBOT_CONFIG=/etc/orbit-keybot/config.json /opt/orbit-keybot/venv/bin/python -m orbitkey.cli "$@"
