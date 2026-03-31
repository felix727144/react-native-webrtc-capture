---
AIGC:
    ContentProducer: Minimax Agent AI
    ContentPropagator: Minimax Agent AI
    Label: AIGC
    ProduceID: "00000000000000000000000000000000"
    PropagateID: "00000000000000000000000000000000"
    ReservedCode1: 304502210085c9c7f5fa277bd804a5391e61b8275dde002681fad0368a41a657aa88e12ea70220356adfb9fc41e11eff74a0328fedbda5a1eac9c72becf6fd38133f1af852237f
    ReservedCode2: 3044022042508f55ae6619e3146daa89bf8484c23572c7011b05ea29df75dc4303a7e07d02206807e3abdf105b2dd847ff1f8915f07f4c723869499e8b35ca28eb9cdfe204df
---

# tvOS Installation

In order to use React Native on tvOS, you will need to use [react-native-tvos](https://www.npmjs.com/package/react-native-tvos).  
tvOS support was deprecated and removed from current/future versions of React Native.  
We strongly recommend using React Native 0.69+ with React 18+  

Change the following dependency in your projects `package.json` file to get started. 
``` 
"react-native": "npm:react-native-tvos@0.69.8-2" 
```

## Adjusting the supported platform version

**IMPORTANT:** Make sure you are using CocoaPods 1.10 or higher.  
You may have to change the `platform` field in your podfile.  
`react-native-webrtc` doesn't support tvOS < 16. Set it to '16.0' or above.
Older versions of tvOS don't support WebRTC.

```
platform :tvos, '16.0'
```
