import json
import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

from stitch.common import DEBUG_CERT_PATH, DEBUG_KEY_PATH, FASTSIGNER_PATH

import config
from utils.logging_config import logger

PATCHED_PREFIX = 'patched'
APK_SUFFIXES = ('.apk', '.xapk')




def find_patched_apk(version_path: Path) -> Path | None:
    """The one place that decides which file in a version dir is the paid artifact.

    A version dir holds both the untouched upstream build and the patched one;
    picking by listdir order used to hand paying users the original.
    """
    candidates = sorted(
        entry for entry in Path(version_path).iterdir()
        if entry.is_file()
        and entry.name.startswith(PATCHED_PREFIX)
        and entry.suffix.lower() in APK_SUFFIXES
    )
    return candidates[0] if candidates else None


def is_bundle(path) -> bool:
    """Whether `path` is a split bundle (.xapk) rather than a single APK.

    Only TOP-LEVEL .apk entries count - the same rule as the store app's
    BundleReader, so the server and the app agree on what a file is. Stitch's
    own is_bundle matches .apk at any depth, and Stitch embeds its helpers in
    patched builds as assets/stitch/*.apk. With it, every single-APK build
    took the bundle branch: the token was embedded into the largest helper,
    and the build itself was re-zipped by make_archive with no signature,
    which the store app refuses as an untrusted signature. A real bundle keeps
    its APKs at the root of the zip.
    """
    with zipfile.ZipFile(path, 'r') as archive:
        return any(
            not info.is_dir() and '/' not in info.filename and info.filename.lower().endswith('.apk')
            for info in archive.infolist()
        )


def _temp_file(suffix: str) -> Path:
    fd, path = tempfile.mkstemp(prefix='patch-', suffix=suffix)
    os.close(fd)
    return Path(path)


def _key_args() -> list:
    """fastsigner's key material, from the same four variables Stitch reads.

    Passwords are handed over as `env:<VAR>` references, never as values: the
    child inherits this process's environment and reads them itself, so no secret
    ever sits in argv. That keeps them out of /proc/<pid>/cmdline, and out of
    app.log - a failed signing raises CalledProcessError, whose message is the
    command line, and the download worker logs it with logger.exception.

    Without KEYSTORE_PATH this falls back to Stitch's bundled debug key
    (CN=Stitch Debug), exactly as Stitch's own sign_apk does.
    """
    keystore = os.environ.get('KEYSTORE_PATH')
    if not keystore:
        return ['--key', str(DEBUG_KEY_PATH), '--cert', str(DEBUG_CERT_PATH)]
    args = ['--ks', keystore, '--ks-pass', 'env:KEYSTORE_PASSWORD']
    if os.environ.get('KEY_ALIAS'):
        args.extend(['--ks-key-alias', os.environ['KEY_ALIAS']])
    if os.environ.get('KEY_PASSWORD'):
        # Omitted, fastsigner reuses the keystore password.
        args.extend(['--key-pass', 'env:KEY_PASSWORD'])
    return args


def _run_signer(targets: list, output: list) -> None:
    args = [str(FASTSIGNER_PATH), *_key_args(), *output, *(str(path) for path in targets)]
    logger.debug('Signing %s', ', '.join(Path(path).name for path in targets))
    result = subprocess.run(
        args,
        stdin=subprocess.DEVNULL,  # a missing password must fail, not wait on a prompt
        capture_output=True,
        text=True,
        timeout=config.SIGNING_TIMEOUT_SECONDS,
    )
    if result.returncode != 0:
        logger.error('fastsigner failed for %s: %s', ', '.join(Path(path).name for path in targets),
                     (result.stderr or result.stdout)[-2000:])
        raise subprocess.CalledProcessError(result.returncode, args)


def sign_apk(bundle_dir: Path, apk_path: Path, output_apk: Path, is_bundle_file: bool) -> None:
    """Sign with Stitch's fastsigner (v2 + v3, zipalign built in).

    Bundles: sign the main apk and every top-level split in place, in one call -
    fastsigner signs them concurrently and rewrites only each file's tail. The
    main apk is passed explicitly because patch_paywall finds it with rglob, so it
    need not sit at the top level; the set keeps it from being signed twice when
    it does. Plain apk: sign apk_path into output_apk.
    """
    if is_bundle_file:
        targets = {Path(apk_path).resolve(), *(path.resolve() for path in Path(bundle_dir).glob('*.apk'))}
        _run_signer(sorted(targets), [])
        return
    _run_signer([apk_path], ['--out', str(output_apk)])


def _embed_token(apk_path: Path, jwt_token: str) -> None:
    with zipfile.ZipFile(apk_path, 'a') as apk:
        apk.writestr('assets/paywall.json', json.dumps({'token': jwt_token}))


def _report(progress, fraction: float, message: str) -> None:
    if progress is not None:
        progress(fraction, message)


def patch_paywall(apk_file: Path, jwt_token: str, output_path=None, progress=None, signer=None) -> Path:
    """Copy the build, embed the app token, re-sign, and return the finished file.

    `signer` is injectable so tests never shell out to fastsigner. Every temp artifact
    is removed in `finally` - a 130MB leak per failed download is not acceptable.
    """
    signer = signer or sign_apk
    apk_file = Path(apk_file)
    output_apk_file = Path(output_path) if output_path else _temp_file(apk_file.suffix)
    temp_apk_file = _temp_file(apk_file.suffix)
    temp_bundle_dir = None
    archive_dir = None
    try:
        _report(progress, 0.15, 'Copying build')
        shutil.copyfile(apk_file, temp_apk_file)
        is_bundle_file = is_bundle(temp_apk_file)

        _report(progress, 0.3, 'Embedding token')
        if is_bundle_file:
            temp_bundle_dir = Path(tempfile.mkdtemp(prefix='patch-bundle-'))
            with zipfile.ZipFile(temp_apk_file, 'r') as zip_file:
                zip_file.extractall(temp_bundle_dir)

            # Pick the main APK from what actually landed on disk rather than
            # from the zip's central directory. These bundles come from
            # third-party mirrors, and extractall strips `..` while the raw
            # arcname does not - joining the arcname would escape the temp dir,
            # and _embed_token opens with 'a', which creates the file.
            extracted = [path for path in temp_bundle_dir.rglob('*.apk') if path.is_file()]
            if not extracted:
                raise ValueError('No APK files found in the XAPK.')
            main_apk = max(extracted, key=lambda path: path.stat().st_size)
            if not main_apk.resolve().is_relative_to(temp_bundle_dir.resolve()):
                raise ValueError('APK path escapes the bundle directory.')

            _embed_token(main_apk, jwt_token)

            _report(progress, 0.45, 'Signing')
            signer(temp_bundle_dir, main_apk, None, True)

            _report(progress, 0.85, 'Repacking bundle')
            archive_dir = Path(tempfile.mkdtemp(prefix='patch-out-'))
            archive = shutil.make_archive(str(archive_dir / 'bundle'), 'zip', temp_bundle_dir)
            shutil.move(archive, str(output_apk_file))
        else:
            _embed_token(temp_apk_file, jwt_token)
            _report(progress, 0.45, 'Signing')
            signer(None, temp_apk_file, output_apk_file, False)

        _report(progress, 0.95, 'Finishing up')
        return output_apk_file
    except BaseException:
        remove_file(output_apk_file)
        raise
    finally:
        remove_file(temp_apk_file)
        if temp_bundle_dir is not None:
            shutil.rmtree(temp_bundle_dir, ignore_errors=True)
        if archive_dir is not None:
            shutil.rmtree(archive_dir, ignore_errors=True)


def remove_file(path) -> None:
    try:
        os.unlink(path)
    except (FileNotFoundError, IsADirectoryError, NotADirectoryError):
        pass
    except OSError as error:
        logger.warning('Could not remove temp file %s: %s', path, error)
