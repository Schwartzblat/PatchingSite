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
apk_mirror_url = "https://www.apkmirror.com"
MAKO_APP_CATEGORY = 'makotv'
MAKO_DOWNLOAD_TEMPLATE = '{}/apk/keshet-broadcasting-ltd/makotv/makotv-{}-release/12-%d7%90%d7%a4%d7%9c%d7%99%d7%a7%d7%a6%d7%99%d7%99%d7%aa-%d7%a1%d7%98%d7%a8%d7%99%d7%9e%d7%99%d7%a0%d7%92-%d7%99%d7%a9%d7%a8%d7%90%d7%9c%d7%99%d7%aa-{}-android-apk-download/'


def get_latest_version_download_link(app_category: str, download_page_template: str) -> Optional[str]:
    versions_html = requests.get(
        f"{apk_mirror_url}/uploads/?appcategory={app_category}", headers=headers
    ).text
    versions = APPLICATION_VERSION_RE.findall(versions_html)
    if len(versions) == 0:
        raise ValueError("No versions found on APKMirror")
    print(versions)
    url = download_page_template.format(apk_mirror_url, versions[0], versions[0])
    download_page = requests.get(url, headers=headers).text
    download_link = download_link_re.findall(download_page)[0]
    click_here_page = requests.get(
        f"{apk_mirror_url}{download_link}", headers=headers
    ).text
    return click_here_re.findall(click_here_page)[0]

def download_latest(app_category: str, download_page_template: str, output_path: str):
    download_link = get_latest_version_download_link().replace("&amp;", "&")
    if not download_link:
        print("[-] Failed to get a valid download link")
        return None
    res = requests.get(f"{apk_mirror_url}{download_link}", headers=headers)
    with open(output_path, "wb") as f:
        f.write(res.content)
    return output_path


if __name__ == "__main__":
    download_latest(MAKO_APP_CATEGORY, MAKO_DOWNLOAD_TEMPLATE, "out.apk")
