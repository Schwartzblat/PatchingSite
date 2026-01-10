import shutil
import zipfile
from pathlib import Path
from typing import Optional
import requests
import re

APPLICATION_VERSION_RE = re.compile(
    '<a class="downloadLink" href=".*?((?:[0-9]+-?)+)-.*?">'
)
download_link_re = re.compile('href=\"([^"]*download/download/[^"]*?)"')
click_here_re = re.compile('href="(.*APKMirror/download.php.id=.*?)"')

headers = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.7103.126 Mobile Safari/537.36"
}
apkpure_url = "https://www.apkpure.com"
MAKO_APP_CATEGORY = '12/com.keshet.mako.VOD/'
MAKO_DOWNLOAD_TEMPLATE = '{}/apk/keshet-broadcasting-ltd/makotv/makotv-{}-release/12-%d7%90%d7%a4%d7%9c%d7%99%d7%a7%d7%a6%d7%99%d7%99%d7%aa-%d7%a1%d7%98%d7%a8%d7%99%d7%9e%d7%99%d7%a0%d7%92-%d7%99%d7%a9%d7%a8%d7%90%d7%9c%d7%99%d7%aa-{}-android-apk-download/'


def get_version_download_link(app_category: str, download_page_template: str) -> Optional[str]:
    versions_html = requests.get(
        f"{apkpure_url}/{app_category}", headers=headers
    ).text
    versions_re = re.compile(f'href="https://apkpure.com/{app_category}/download/([^"]*)\"')
    # versions = versions_re.findall(versions_html)
    # if len(versions) == 0:
    #     raise ValueError("No versions found on APKMirror")
    # print(versions)
    # url = download_page_template.format(apk_mirror_url, versions[0], versions[0])
    # download_page = requests.get(url, headers=headers).text
    # download_link = download_link_re.findall(download_page)[0]
    # click_here_page = requests.get(
    #     f"{apk_mirror_url}{download_link}", headers=headers
    # ).text
    # return click_here_re.findall(click_here_page)[0]


def get_latest_version_download_link(package_name: str) -> Optional[str]:
    return f'https://d.apkpure.com/b/XAPK/{package_name}?version=latest'

def is_bundle(filepath: Path) -> bool:
    with zipfile.ZipFile(filepath, 'r') as zip_file:
        for file in zip_file.namelist():
            if file.endswith('.apk'):
                return True
    return False

def get_extension(filepath: Path) -> str:
    if is_bundle(filepath):
        return 'xapk'
    return 'apk'

def download_latest(package_name: str, out_name: str):
    download_link = get_latest_version_download_link(package_name).replace("&amp;", "&")
    if not download_link:
        print("[-] Failed to get a valid download link")
        return None
    res = requests.get(download_link, headers=headers)
    with open(out_name, "wb") as f:
        f.write(res.content)
    new_name = f"{out_name}.{get_extension(Path(out_name))}"
    shutil.move(out_name, new_name)
    return new_name


if __name__ == "__main__":
    download_latest('com.whatsapp', "out")
