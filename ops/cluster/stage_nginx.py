#!/usr/bin/env python3
"""Generate private staged Nginx config. Never reloads or activates it."""
import argparse
from pathlib import Path
import re
import shutil


def common_proxy(context, node, token):
    return f'''proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-IAFM-Ingress-Node "{node}";
        proxy_set_header X-IAFM-Ingress-Token "{token}";
        proxy_set_header X-Starhub-Ingress-Node "{node}";
        proxy_set_header X-Starhub-Ingress-Token "{token}";
        proxy_cookie_path /{context}/ /;
        proxy_cookie_path /{context} /;
        proxy_redirect /{context}/ /;
        proxy_connect_timeout 3s;
        proxy_read_timeout 60s;
        proxy_intercept_errors on;'''


def identity(node):
    return f'''location = /api/server {{
        default_type application/json;
        add_header Cache-Control "no-store" always;
        return 200 '{{"serverId":{node}}}';
    }}'''


def app_config(name, context, node, token, primary_http_origin=None, coordinator_node=1, logical_shard=None, coordinator_port=18081):
    if not isinstance(node,int) or isinstance(node,bool) or node<1 or not isinstance(coordinator_node,int) or isinstance(coordinator_node,bool) or coordinator_node<1: raise ValueError('Invalid physical node identity')
    if logical_shard is None: logical_shard=node if node in (1,2) else None
    if logical_shard not in (1,2): raise ValueError('Explicit logical shard 1 or 2 required')
    if coordinator_port not in range(1024,65536): raise ValueError('Invalid private coordinator port')
    host=name+'.zhongjiehuang.com';port=8080 if node==coordinator_node else 18081
    if node!=coordinator_node: port=coordinator_port
    certificates='/etc/letsencrypt/live/'+host if node==1 else '/etc/nginx/tls/four-project/'+host
    proxy=common_proxy(context,logical_shard,token)
    if name=='starhub': proxy=proxy.replace('proxy_read_timeout 60s;','proxy_read_timeout 360s;')
    realtime_proxy=proxy.replace('proxy_read_timeout 360s;','proxy_read_timeout 1800s;')
    acme=f'root /var/www/{name}-acme;' if node==1 else f'proxy_pass {primary_http_origin}; proxy_set_header Host $host; proxy_connect_timeout 3s; proxy_read_timeout 5s;'
    limits=''
    if name=='ucost': limits='limit_req_zone $binary_remote_addr zone=ucost_auth:10m rate=10r/m;\n'
    if name=='starhub': limits='limit_req_zone $binary_remote_addr zone=starhub_api:10m rate=30r/m;\nlimit_req_zone $binary_remote_addr zone=starhub_access:10m rate=5r/m;\nlimit_conn_zone $binary_remote_addr zone=starhub_realtime:1m;\n'
    extras=''
    if name=='iafm': extras='''location /vendor/ { alias /var/www/iafm-vendor/; expires 30d; }
    location /Investment_and_Financial_Management/ { rewrite ^/Investment_and_Financial_Management/(.*)$ /$1 permanent; }
'''
    if name=='ucost': extras=f'''location ~ "^/api/(login|register|password)[;/]" {{ return 404; }}
    location ~ ^/api/(login|register|password)$ {{
        limit_req zone=ucost_auth burst=10 nodelay; limit_req_status 429;
        rewrite ^/api/(.*)$ /ucost/api/$1 break; proxy_pass http://127.0.0.1:{port};
        {proxy}
    }}
'''
    if name=='starhub': extras=f'''client_body_buffer_size 6m;
    proxy_request_buffering off;
    client_body_timeout 15s;
    access_log /var/log/nginx/starhub-access.log;
    error_log /var/log/nginx/starhub-error.log;
    location = /realtime {{
        limit_conn starhub_realtime 2;
        proxy_pass http://127.0.0.1:{port}/starhub/realtime;
        {realtime_proxy}
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Origin $http_origin;
        proxy_send_timeout 30s;
        proxy_buffering off;
        proxy_request_buffering off;
    }}
    location = /_access {{
        limit_req zone=starhub_access burst=5 nodelay; limit_req_status 429;
        proxy_pass http://127.0.0.1:{port}/starhub/_access;
        {proxy}
    }}
    location ~ "^/_access[;/]" {{ return 404; }}
    location ~ ^/api/(chat|run|batch|check)$ {{
        proxy_buffering off; gzip off;
        limit_req zone=starhub_api burst=5 nodelay; limit_req_status 429;
        rewrite ^/api/(chat|run|batch|check)$ /starhub/api/$1 break;
        proxy_pass http://127.0.0.1:{port};
        {proxy}
    }}
'''
    headers=''
    if name in ('iafm','ucost'):
        headers='''add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'" always;
    add_header Cache-Control "no-store" always;'''
    if name=='iafm': headers+='\n    add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()" always;'
    return limits+f'''server {{
    listen 80;
    server_name {host};
    location ^~ /.well-known/acme-challenge/ {{ {acme} }}
    location / {{ return 301 https://{host}$request_uri; }}
}}
server {{
    listen 443 ssl;
    server_name {host};
    ssl_certificate {certificates}/fullchain.pem;
    ssl_certificate_key {certificates}/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;
    add_header Strict-Transport-Security "max-age=31536000" always;
    {headers}
    client_max_body_size {'2100k' if name=='ucost' else '18m' if name=='starhub' else '2m'};
    root /opt/tomcat/webapps/{context};
    index index.html;
    error_page 502 503 504 =503 @cluster_unavailable;
    {identity(node)}
    location ~* "^/(?:api/)?internal(?:/|;|$)" {{ return 404; }}
    location ~* "^/(?:WEB-INF|META-INF)(?:/|;|$)" {{ return 404; }}
    location ~* "\\.(?:class|java|properties|sql|log|jar|war)$" {{ return 404; }}
    location ~ /\\. {{ return 404; }}
    {extras}
    location / {{ try_files $uri $uri/ @application; }}
    location @application {{
        rewrite ^/(.*)$ /{context}/$1 break;
        proxy_pass http://127.0.0.1:{port};
        {proxy}
    }}
    location @cluster_unavailable {{
        default_type application/json;
        charset utf-8;
        add_header Cache-Control "no-store" always;
        return 503 '{{"status":"failure","code":"CLUSTER_UNAVAILABLE","message":"服务器故障，请等待恢复"}}';
    }}
}}
'''


def main():
    p=argparse.ArgumentParser();p.add_argument('--node-id',type=int,required=True);p.add_argument('--token-file',required=True);p.add_argument('--output',required=True);p.add_argument('--primary-http-origin');p.add_argument('--coordinator-node',type=int,default=1);p.add_argument('--logical-shard',type=int,choices=[1,2]);p.add_argument('--coordinator-port',type=int,default=18081);args=p.parse_args()
    if args.node_id<1 or args.coordinator_node<1: raise SystemExit('Physical node IDs must be positive')
    if args.node_id!=1 and not re.fullmatch(r'http://[0-9.]+(?::80)?',args.primary_http_origin or ''): raise SystemExit('Explicit HTTP challenge origin required')
    if args.node_id>2 and args.logical_shard is None: raise SystemExit('Explicit logical shard required for this physical member')
    token=Path(args.token_file).read_text().strip()
    if not re.fullmatch(r'[A-Za-z0-9_-]{32,}',token): raise SystemExit('Invalid private ingress token')
    output=Path(args.output);sites=output/'sites';sites.mkdir(parents=True,exist_ok=True);output.chmod(0o700);sites.chmod(0o700)
    for existing in Path('/etc/nginx/sites-enabled').iterdir():
        if existing.is_file(): (sites/existing.name).write_text(existing.read_text())
    for name,context in [('iafm','Investment_and_Financial_Management'),('starhub','starhub'),('ucost','ucost')]:
        path=sites/name;path.write_text(app_config(name,context,args.node_id,token,args.primary_http_origin,args.coordinator_node,args.logical_shard,args.coordinator_port));path.chmod(0o600)
    for name in ('zhongjiehuang','zhongjie-portfolio-ip'):
        path=sites/name
        if not path.exists(): continue
        text=path.read_text()
        replacement='''location / {
        root /opt/tomcat/webapps/ROOT;
        index index.html;
        try_files $uri $uri/ =404;
        error_page 404 /404.html;
    }
    location = /404.html { root /opt/tomcat/webapps/ROOT; internal; }
    '''+identity(args.node_id)+'''
    location ~* "^/(?:WEB-INF|META-INF)(?:/|;|$)" { return 404; }
    location ~ /\\. { return 404; }'''
        text,count=re.subn(r'location / \{\s*proxy_pass http://127\.0\.0\.1:8080;[^{}]*\}',lambda _m:replacement,text)
        if count<1: raise RuntimeError('Expected portfolio route not found; preserve and inspect manually')
        text=re.sub(r'location \^~ /vendor/ \{[^{}]*\}', 'location ^~ /vendor/ { alias /var/www/iafm-vendor/; expires 30d; }', text)
        path.write_text(text);path.chmod(0o600)
    nginx=Path('/etc/nginx/nginx.conf').read_text()
    if '/etc/nginx/sites-enabled/*' not in nginx: raise RuntimeError('Unexpected Nginx include layout')
    (output/'nginx-test.conf').write_text(nginx.replace('/etc/nginx/sites-enabled/*',str(sites/'*')))
    (output/'nginx-test.conf').chmod(0o600)
    print('Private Nginx configuration staged; no live configuration was changed.')


if __name__=='__main__': main()
