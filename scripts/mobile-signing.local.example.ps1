# Copy this file to mobile-signing.local.ps1 if you prefer a local PowerShell
# profile instead of encrypted storage. Do not commit the copied file.
$env:VLUGBOEK_ANDROID_KEYSTORE = 'C:\Development\Vlugboek\vlugboekkeystore'
$env:VLUGBOEK_ANDROID_KEY_ALIAS = 'key0'
$env:VLUGBOEK_ANDROID_KEYSTORE_PASSWORD = '<keystore password>'
$env:VLUGBOEK_ANDROID_KEY_PASSWORD = '<key password>'
