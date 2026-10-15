"""Reject framework legacy references, including reflective class-name strings, in native modules."""
FORBIDDEN = (
    b"de/robv/android/xposed", b"de.robv.android.xposed",
    b"android/app/AndroidAppHelper", b"android.app.AndroidAppHelper",
    b"android/content/res/XResources", b"android.content.res.XResources",
    b"android/content/res/XModuleResources", b"android.content.res.XModuleResources",
    b"android/content/res/XResForwarder", b"android.content.res.XResForwarder",
)

def reject_legacy_dex(data: bytes, label: str) -> None:
    for reference in FORBIDDEN:
        assert reference not in data, f"Framework legacy reference {reference.decode()} in {label}"
