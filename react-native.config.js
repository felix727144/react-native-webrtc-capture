'use strict';

const macSwitch = '--use-react-native-macos';

if (process.argv.includes(macSwitch)) {
    process.argv = process.argv.filter(arg => arg !== macSwitch);
    process.argv.push('--config=metro.config.macos.js');
    module.exports = {
        reactNativePath: 'node_modules/react-native-macos'
    };
} else {
    module.exports = {
        dependencies: {
            'react-native-webrtc': {
                platforms: {
                    android: {
                        sourceDir: '.',
                        packageInstance: 'new WebRTCModulePackage()'
                    }
                }
            }
        }
    };
}
