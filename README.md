# ClassCheck – Attendance Check-in App (Prototype)

University project for **Mobile Programming Engineering I**.

## What this is

A clean Material Design 3 prototype of a university attendance system using:
- QR Code
- 6-digit code

Students can check in by scanning a QR or typing the code.  
Teachers can start a session and see a live list of checked-in students.

## How to open in Android Studio

1. Download / copy the whole `ClassCheck` folder
2. Open **Android Studio**
3. Choose **Open** → select the `ClassCheck` folder
4. Wait for Gradle sync to finish
5. Run the app on an emulator or real device

## Screens included

### Student
- Welcome / Role selection
- Home (today’s classes)
- Check-in (camera placeholder + manual 6-digit code)
- Success confirmation
- Attendance history
- Profile

### Teacher
- Home (list of classes)
- Active Session (QR placeholder + big 6-digit code + live list)
- Session history

## Tech stack
- Kotlin
- Jetpack Compose
- Material Design 3
- Navigation Compose

## Next steps (for the real project)
- Add real QR generation & scanning (CameraX + ML Kit)
- Backend (Firebase / Supabase) for real multi-user data
- Authentication (university email)
- Time-limited codes
- Better error handling & offline support

---

This is a working UI prototype that already follows Android design guidelines and can be used for the mockup presentation.
