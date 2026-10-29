"""Cloud transport retries connection establishment, never replays a submitted write."""
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

SESSION = requests.Session()
SESSION.mount('https://', HTTPAdapter(max_retries=Retry(
    total=2, connect=2, read=0, status=0, other=0, backoff_factor=.5)))

def request(method, url, **kwargs):
    return SESSION.request(method, url, timeout=(15, 90), **kwargs)
