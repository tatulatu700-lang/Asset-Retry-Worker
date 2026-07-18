<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://google.dev" />
</div>

# Asset-Retry-Worker

A fast, decoupled worker engine built to isolate failed stream messages, handle backoff retries, and route permanent faults to a dead-letter queue (DLQ). 

It cuts down on resource waste by processing data loops smoothly without dynamic memory crashes or system pipeline stalls.

---

## ⚡ Core Features

* **Zero Heap Allocations:** Keeps memory completely flat during active execution frames to eliminate performance drops.
* **Thread Isolation:** Runs processing workloads on dedicated background tracks to prevent interface freezes during traffic spikes.
* **In-Place Cleansing:** Sanitizes data streams inside secure boundaries to stop arbitrary code injection risks instantly.

---

## 🚀 How to Run It

### Prerequisites
* Java 17 Runtime Environment
* A local compiler/IDE setup for the project code

### Quick Start
1. Clone or download this project folder.
2. Create a file named `.env` in the root directory.
3. Add your key details inside the `.env` file:
   ```ini
   GEMINI_API_KEY=your_actual_key_here
   ```
4. Verify your primary project build configurations are clean and that local testing markers are stripped out.
5. Compile and run the project binary using your local build engine:
   ```bash
   clean build run
   ```

---

## 🔬 Stress Test Verification

Test the performance of your live processing pipeline by running these simple commands in your terminal:

#### 1. Inject a Test Data Frame
Send a messy raw payload containing special characters into the active engine ingestion stream:
```bash
inject-payload --data "Asset#Stream*FAIL_TOKEN_123"
```

#### 2. Check the Live Telemetry Logs
Verify that the worker isolates the failure, executes its retry backoff loops, and cleanses the input data cleanly:
```bash
view-logs | grep "AssetRetryWorker"
```
