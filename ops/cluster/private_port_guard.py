from pathlib import Path
import sys
try: valid=int(Path('/proc/sys/net/ipv4/ip_unprivileged_port_start').read_text().strip())>=1024
except (OSError,ValueError):valid=False
if not valid:print('Private low ports are not protected; startup refused.',file=sys.stderr)
raise SystemExit(0 if valid else 1)
