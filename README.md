# 🌦 PAGASA Bulletin AI Translator API  
**Spring Boot + PDF Parser + OpenAI Translation**

---

## 📘 Overview

This project is a **Spring Boot reactive API** that automatically reads the latest **PAGASA Tropical Cyclone Bulletin (TCB)** PDF, extracts key weather data (cyclone name, movement, and warnings), and optionally translates it into local Philippine languages using **AI (OpenAI API)**.

It allows you to:
- 📥 Fetch the **latest or specific PAGASA bulletin** directly from the official PAGASA servers.
- 🧾 **Parse PDF text** and extract structured information like:
  - Cyclone name, classification, and location  
  - Movement direction and speed  
  - Active Tropical Cyclone Wind Signals (TCWS)  
  - Land and coastal hazards  
- 🤖 **Translate the bulletin** (e.g., to Ilocano, Cebuano, Tagalog, etc.) for local understanding.

---

## ⚙️ Tech Stack

| Component | Description |
|------------|-------------|
| **Spring Boot 3 (WebFlux)** | Reactive REST API framework |
| **Reactor / Mono** | Non-blocking asynchronous data flow |
| **Jsoup** | HTML parser for finding latest bulletin links |
| **PDFBox** | Extracts and parses PDF text content |
| **OpenAI API** | Used for AI-powered translation |
| **Java 17+** | Minimum Java version required |

---

## 🧩 Main Features

### ✅ 1. Automatic Bulletin Fetch
- The app scans PAGASA’s official bulletin directory:
https://pubfiles.pagasa.dost.gov.ph/tamss/weather/bulletin/

- Finds the latest TCB PDF or one that matches a given storm name (e.g. “mirasol”).

---

### ✅ 2. PDF Parsing
- Extracts and summarizes only the important sections:
- **Cyclone details**
- **Tropical Cyclone Wind Signals (TCWS)**
- **Hazards affecting land & coastal waters**
- **Track and intensity outlook**
- Returns structured JSON output (no messy text).

---

### ✅ 3. AI Translation
- Integrates with **OpenAI’s GPT model** to translate important sections (`cyclone` and `tcws`) into a target language.
- Keeps responses **brief, accurate, and natural-sounding**.

---

### ✅ 4. Secure PDF Access
- Uses a **whitelist checker** to ensure only official PAGASA domains are allowed (`pubfiles.pagasa.dost.gov.ph`, `cdn.pagasa.dost.gov.ph`).
- Prevents unsafe or arbitrary URL access.

---

## 🚀 API Endpoints

### **1️ GET `/api/latest-parsed`**

**Description:**  
- Fetches and parses the latest PAGASA bulletin (or a specific one if `pdf` param is provided).

**Parameters:**

- | Name | Type | Default | Description |
|------|------|----------|--------------|
| `raw` | `boolean` | `false` | Include full raw PDF text |
| `pdf` | `string` | _optional_ | Bulletin name or partial name (e.g. “mirasol”) |

**Example Request:**

- GET http://localhost:8080/api/latest-parsed?raw=true&pdf=mirasol


**Sample Response:**

```json
{
  "latestUrl": "https://pubfiles.pagasa.dost.gov.ph/tamss/weather/bulletin/TCB%232_mirasol.pdf",
  "cyclone": {
    "name": "MIRASOL",
    "classification": "Tropical Depression",
    "location": {
      "text": "The center of Tropical Depression MIRASOL was estimated at 115 km NW of Calayan, Cagayan."
    },
    "movement": {
      "text": "Southwestward at 20 km/h"
    }
  },
  "sections": {
    "tcws": "TCWS No. 1 over Batanes and Babuyan Islands",
    "hazards_land": "Heavy rainfall and strong winds expected.",
    "hazards_coastal": "Very rough seas expected.",
    "track_outlook": "Forecast to move southwestward and weaken."
  },
  "rawText": "Full extracted PDF text here..."
}
```

### **2️ GET /api/latest-translate**

**Description:**
- Fetches and translates only the cyclone + TCWS info into a target local language.

**Parameters:**

- Name	Type	Default	Description
lang	string	en	Target language (e.g. ilo, ceb, tl)
pdf	string	optional	Bulletin name (e.g. “mirasol”)

**Example Request:**

- GET http://localhost:8080/api/latest-translate?lang=ilo&pdf=mirasol

**Sample Response:** 

``` json 
{ "language": "ilo",
  "generator": "ai",
  "latestUrl": "https://pubfiles.pagasa.dost.gov.ph/tamss/weather/bulletin/TCB%232_mirasol.pdf",
  "cyclone": {
    "name": "MIRASOL",
    "classification": "Tropical Depression",
    "location": {
      "text": "Ti sentro ti Tropical Depression MIRASOL ket adda idiay Calayan, Cagayan."
    },
    "movement": {
      "text": "Agsalsalogsog iti 20 km/h."
    }
  },
  "tcws": "TCWS No. 1: Batanes ken Babuyan Islands – nakaro nga angin ken nalaylayos a baybay."
}
```

---
## 🧠 AI Integration

- The app uses OpenAI’s /v1/chat/completions endpoint for translation.

- Model: gpt-4.1-mini 

- Input: Cyclone + TCWS text

- Output: Translated summary in the requested language

- This ensures efficient token use and avoids 429 (rate limit) errors by:

  - Sending only short, essential text

  - Adding automatic retries and small delays

🔒 Security

- Feature Description

- ✅ WhitelistPdfHostChecker	Blocks non-PAGASA URLs

- ✅ Limited OpenAI payloads	Prevents excessive API usage

- ✅ Optional retry/backoff	Handles 429 rate-limit errors

🧾 Configuration

- application.properties:

- app.pagasa.base-url=https://pubfiles.pagasa.dost.gov.ph/tamss/weather/bulletin/

- app.allowed-pdf-hosts[0]=pubfiles.pagasa.dost.gov.ph

- app.allowed-pdf-hosts[1]=cdn.pagasa.dost.gov.ph

- openai.api-key=YOUR_OPENAI_API_KEY

🧪 Example Flow

- 1️ User calls:
	- GET /api/latest-parsed?pdf=mirasol
	- Downloads and parses PAGASA’s latest PDF.

- 2️ Then calls:
	- GET /api/latest-translate?lang=ilo&pdf=mirasol
	- AI translates only the relevant parts into Ilocano.

- 3️ Result:
	- Clean, localized JSON output for apps, dashboards, or mobile alerts.

-  Run Locally

# Clone the repository

- git clone https://github.com/yourusername/pagasa-ai-translator.git

- cd pagasa-ai-translator

# Build and run

mvn spring-boot:run

- Then open:

	- http://localhost:8080/api/latest-parsed

- or

	- http://localhost:8080/api/latest-translate?lang=ilo

🌐 Example Use Cases

- ✅ Local Government Dashboards
 	- Show translated storm warnings automatically.

- ✅ SMS or Chatbot Alerts
	- Send short AI-translated summaries in regional languages.

- ✅ Weather Apps / AI Assistants
	- Integrate with frontend apps for localized storm updates.

💡 Future Enhancements

- ➡ Add caching for parsed results

- ➡ Add historical bulletin lookup (by date)

- ➡ Add Telegram / SMS notification integration

---

## 🚀 API Endpoints for AWS

- 🌐 link : https://0vo8zetg7i.execute-api.ap-southeast-2.amazonaws.com/dev

** 1 User calls: **

a.) GET /api/latest-parsed?raw=false  (latest)

b.) GET /api/latest-parsed?raw=false&pdf=TCB#3_salome.pdf (specific)

➡ Downloads and parses PAGASA’s latest PDF.

** 2️ Then tranalate pag asa details : **

a.) GET /api/latest-translate?lang=ilo (latest)

b.) GET /api/latest-translate?lang=ilo&pdf=TCB#3_salome.pdf (specific)

 - ➡ AI translates only the relevant parts into Ilocano.

--- 
👨‍💻 Author

Jerold David L. Caballero

Senior Java Developer/Integration Engineer

