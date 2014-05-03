/*
 Based on example by Tom Igoe included in Arduino IDE
 at File -> Examples -> Stepper -> stepper_oneRevolution

 Modified to suit pinouts of Freetronics HBridge Shield
*/

#include <Stepper.h>
#include <AccelStepper.h>
#include <adk.h>

USB Usb;
ADK adk(&Usb, "Papercloud", // Manufacturer Name
              "Gladys", // Model Name
              "Example sketch for the USB Host Shield", // Description (user-visible string)
              "1.0", // Version
              "http://www.tkjelectronics.dk/uploads/ArduinoBlinkLED.apk", // URL (web page to visit if no installed apps support the accessory)
              "123456789"); // Serial Number (optional)


const int stepsPerRevolution = 48;
const int stepsPerPivot = 24;
                                     
const int reallyFar = stepsPerRevolution * 1000; // Go really far
const int speedConst = 260;
const int acceleration = 100.0;

uint32_t timer;
boolean connected;

// initialize the stepper library using the default pins on the HBridge Shield:
AccelStepper rightStepper(AccelStepper::FULL4WIRE, 4, 7, 3, 2);
AccelStepper leftStepper(AccelStepper::FULL4WIRE, 16, 17, 15, 14);

const int enableA = 6;
const int enableB = 5;

void setup() {
  
  pinMode(enableA, OUTPUT);
  pinMode(enableB, OUTPUT);

  leftStepper.setMaxSpeed(speedConst);
  leftStepper.setAcceleration(acceleration);

  rightStepper.setMaxSpeed(speedConst);
  rightStepper.setAcceleration(acceleration);

  Serial.begin(57600);
  while (!Serial); // Wait for serial port to connect - used on Leonardo, Teensy and other boards with built-in USB CDC serial connection
  if (Usb.Init() == -1) {
    Serial.print("\r\nOSCOKIRQ failed to assert");
    while (1); // halt
  }
  Serial.print("\r\nArduino Blink LED Started");
}

void moveEnable() {
  digitalWrite(enableA, HIGH);
  digitalWrite(enableB, HIGH);
}

void moveDisable() {
  digitalWrite(enableA, LOW);
  digitalWrite(enableB, LOW);
}

void moveStop() {
  leftStepper.stop();
  rightStepper.stop();
}

void moveForward() {
  leftStepper.moveTo(reallyFar);
  rightStepper.moveTo(-reallyFar);
}

void moveBack() {
  leftStepper.moveTo(-reallyFar);
  rightStepper.moveTo(reallyFar);
}

void moveLeft() {
  leftStepper.move(reallyFar);
  rightStepper.move(reallyFar);
}

void moveRight() {
  leftStepper.move(-reallyFar);
  rightStepper.move(-reallyFar);
}

void moveDirection(char dir) {
  if (dir == 'F') {
    moveForward();
  } else if (dir == 'S') {
    moveStop(); 
  } else if (dir == 'L') {
    moveLeft();
  } else if (dir == 'R') {
    moveRight();
  } else if (dir == 'B') {
    moveBack();
  }
}

void moveSpeed(int8_t speed)
{
 leftStepper.setSpeed((float)speed);
 rightStepper.setSpeed((float)-1 * (float)speed);
}

void loop() {

  if (rightStepper.distanceToGo() == 0 && leftStepper.distanceToGo() == 0) {
    moveDisable();
  } else {
    moveEnable();
  }
  
  rightStepper.run();
  leftStepper.run();
  
  Usb.Task();

  if (adk.isReady()) {
    if (!connected) {
      connected = true;
      Serial.print(F("\r\nConnected to accessory"));
    }

    uint8_t msg[2];
    uint16_t len = sizeof(msg);
    uint8_t rcode = adk.RcvData(&len, msg);
    if (rcode && rcode != hrNAK) {
      Serial.print(F("\r\nData rcv: "));
      Serial.print(rcode, HEX);
    } else if (len > 0) {
      int leftSpeed = BitShiftCombine(msg[0], msg[1]);
      Serial.print(F("\r\nData Packet: "));
      Serial.print(leftSpeed);
//      moveDirection(msg[0]);

      moveSpeed(leftSpeed);
    }

//    if (millis() - timer >= 1000) { // Send data every 1s
//      timer = millis();
//      rcode = adk.SndData(sizeof(timer), (uint8_t*)&timer);
//      if (rcode && rcode != hrNAK) {
//        Serial.print(F("\r\nData send: "));
//        Serial.print(rcode, HEX);
//      } else if (rcode != hrNAK) {
//        Serial.print(F("\r\nTimer: "));
//        Serial.print(timer);
//      }
//    }
  } else {
    if (connected) {
      connected = false;
      Serial.print(F("\r\nDisconnected from accessory"));
    }
  }
}

int BitShiftCombine( unsigned char x_high, unsigned char x_low)
{
  int combined; 
  combined = x_high;              //send x_high to rightmost 8 bits
  combined = combined<<8;         //shift x_high over to leftmost 8 bits
  combined |= x_low;                 //logical OR keeps x_high intact in combined and fills in                                                             //rightmost 8 bits
  return combined;
}

