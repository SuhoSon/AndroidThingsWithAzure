# Etcher
https://www.balena.io/etcher/

# Android Studio
https://developer.android.com/studio/?hl=ko

# Android SDK
https://developer.android.com/studio/releases/platform-tools.html?hl=ko

# Putty
https://www.putty.org/

# Azure Portal
https://portal.azure.com/#home

# Cloud Shell commands

az extension add –name azure-cli-iot-ext az iot hub device-identity create –hub-name {YourIoTHubName} --device-id MyAndroidThingsDevice

## DeviceString
az iot hub device-identity show-connection-string --hub-name {YourIoTHubName} --device-id MyAndroidThingsDevice --output table
## HubEndPointString
az iot hub show --query properties.eventHubEndpoints.events.endpoint --name {YourIoTHubName}
## HubPathString
az iot hub show --query properties.eventHubEndpoints.events.path --name {YourIoTHubName}
## HubSasKeyString
az iot hub policy show --name service --query primaryKey --hub-name {YourIoTHubName}

# Android Things Console
https://partner.android.com/things/console

# Raspberry Pi 3 WiFi connect

am startservice \
-n com.google.wifisetup/.WifiSetupService \
-a WifiSetupService.Connect \
-e ssid {WiFi ID} \
-e passphrase {WiFi password}

logcat –d | grep WifiConfigurator 
