# security

if you find something that could expose credentials, control somebody else's
camera, or turn a normal bug into a real user problem, please use github's
private vulnerability reporting for this repo. do not drop a working exploit,
password, device id, or token into a public issue.

the macos cli does not need dji account credentials or camera wifi passwords
for its current ble configuration commands. the android viewer does need lan
credentials so it can put the camera on the phone's network; those are stored
with android keystore and should never be printed to logs.

captures and test fixtures must use fake secrets. “i'll change it later” is how
real passwords end up living in git history forever.
