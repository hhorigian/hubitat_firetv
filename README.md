# hubitat_firetv
Driver for FireTV using ADB

Allows using FireTV directly from Hubitat without any other hardware in the middle.
Requirement:
 *    1. Go to settings into your FireTV, and Developer Options → ADB Debugging: ON.
 *    2. Intall the driver, setup the  IP and save.
 *    3. Click in any command from the Device page. TV will ask for authorization.  
 *    4. Check the "Alows Allow".
  

Settings:  
Navigate to the gear icon on the far right of the Fire TV home screen.

Go to 'My Fire TV':  
Select My Fire TV (or "Device & Software" on some televisions).

Go to 'About':  
Select About.

Activate Developer Options:  
Highlight the name of your device (e.g., "Fire TV Stick 4K") and press the center button on your remote 7+ times.

A message will appear at the bottom:  
"No need, you are already a developer".

Access New Menu:  
Press the back button.

You will now see Developer Options directly above the "About" menu.

Enable Apps:  
Inside Developer Options, select "Install unknown apps" to turn them on for specific apps like Downloader.
