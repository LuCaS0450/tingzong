"""Supabase Management API calls using a DPAPI-protected local token."""
import json
from cloud_credentials import ROOT, read_config
from cloud_http import request

def management(path, method='GET', payload=None, raw=False):
    config=read_config()
    token=config.get('access_token')
    if not token:
        raise RuntimeError('Fill access_token locally; never send it in chat')
    if not path.startswith('/v1/projects/'+config['project_ref']):
        raise RuntimeError('Management calls are restricted to the configured project')
    response=request(method,'https://api.supabase.com'+path,json=payload,
                     headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    if not response.ok:
        # Response may contain credential values. Do not propagate into console.
        (ROOT / '.private/management-error.txt').write_bytes(response.content)
        raise RuntimeError(f'Management request failed with HTTP {response.status_code}') from None
    return response.content if raw else response.json()

def project_path(suffix):
    return '/v1/projects/'+read_config()['project_ref']+suffix
