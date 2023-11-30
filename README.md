# flyDetect
This app was developed for my master's thesis at the University of Oslo (UiO). 
The thesis was supervised by Prof. Paulo Ferreira and written in English.

## About
flyDetect is an app capable of reliably detecting flight when onboard most airliners. 
It does this using only the built-in sensors of the device it's running on - the accelerometer and barometer.

## Results
Out of 31 flights, flyDetect successfully detected takeoff in 100% of them and landing in 95.7%.
Because of the simplicity of the algorithms responsible for detection and the use of low-power sensors, flyDetect has an impressively low power consumption.
With the algorithm in the "not flying" state, flyDetect consumes an estimated 0.40 mA (approximately 0.01% battery drained per hour on the test device).

Interested in more details about the results or how the app works? [Feel free to read the thesis here.](https://www.duo.uio.no/bitstream/handle/10852/104303/flyDetect---Jonas-Reinholdt-master-thesis.pdf?sequence=1&isAllowed=y)
