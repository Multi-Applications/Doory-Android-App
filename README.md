<p align="center">
  <img src="https://github.com/Multi-Applications/Doory-Android-App/blob/main/app/src/main/assets/www/icons/apple-touch-icon.png" alt="Doory Icon" width="128" />
</p>

<h1 align="center">Doory Android App</h1>

<p align="center">
  A native Android application for Doory.
</p>

<p align="center">
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat&logo=android&logoColor=white" alt="Android 8.0+"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-green?style=flat" alt="MIT License"></a>
</p>

> **IMPORTANT NOTICE**
>
> This project was **NOT** created by the developer of Doory and is **NOT** affiliated with or connected to Doory! It is an independent project by **Multi Applications**. Doory and the Doory developer are not involved in this Project!

## Information

This app wraps the Doory web editor into an native Android application. Assets like web files, fonts, and icons are bundled directly into the app, basic features and local guest drafts work completely offline! An internet connection is only needed when loading online templates, stock assets, or signing into your account via `doory.art`. To make the web editor feel more native on mobile devices, the app adds system file picker integration, direct exports to the `Downloads/Doory` folder, and keeps your login session active across app launches.

## Update Mechanism

The app checks GitHub releases once a day when opened, or manually when you tap **About Doory → Check for updates**. Instead of requiring a full APK reinstall for every change, it fetches the updated web bundle directly from `ghcr.io/shaongitbd/doory-frontend`. Before applying an update, the downloaded image is verified via SHA-256 digests and extracted cleanly in the background. Your local WebView data, saved drafts, and settings remain untouched during this process.

## Attribution

Doory was created by **Siam** and is licensed under the **Attribution Assurance License**.

- Source: [github.com/shaongitbd/doory](https://github.com/shaongitbd/doory)