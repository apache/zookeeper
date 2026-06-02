---
name: Delta metrics PathTrie progress
description: Stato raccolta metriche Delta per PathTrie C1-C4 (EVO/LLM/RND) - in corso
type: project
---

## Stato lavoro Delta PathTrie (aggiornato 2026-06-01)

### Cosa e' stato fatto
- JaCoCo e PIT raccolti per TUTTE le 12 combinazioni (C1-C4 x EVO/LLM/RND)
- SonarCloud analisi lanciate per tutte le combinazioni su branch separati (C1-EVO, C1-LLM, C1-RND, C2-EVO, ecc.)
- CSV scritti ma da RIVEDERE (valori troppo uniformi, verificato che e' corretto - vedi sotto)
- PathTrie originale ripristinato

### Scoperta importante: C1=C2 (file identici, stesso MD5)
- `PathTrie_c1.java` e `PathTrie_C2.java` hanno lo stesso hash md5: `b9065bdcc4faac756b28af12795cab87`
- C3 differisce da C1 solo per commenti/whitespace/javadoc (stesso bytecode)
- C4 ha modifiche strutturali reali (campi private, costanti ROOT_PATH/INVALID_PATH_PREFIX, getter)
- MA il bytecode risultante e' quasi identico (453 istruzioni, 104 linee, 32 branch per tutte le versioni)
- Quindi i valori JaCoCo/PIT identici tra C1-C4 sono CORRETTI

### Dati raccolti

#### JaCoCo + PIT (tutti verificati)

| Cx-Suite | JaCoCo Statement | JaCoCo Branch | PIT Line | PIT Mutation | PIT Strength |
|----------|-----------------|---------------|----------|-------------|-------------|
| C1-EVO   | 75.0%           | 90.6%         | 99%      | 68%         | 68%         |
| C1-LLM   | 75.0%           | 93.8%         | 99%      | 95%         | 95%         |
| C1-RND   | 65.4%           | 68.8%         | 86%      | 74%         | 88%         |
| C2-EVO   | 75.0%           | 90.6%         | 99%      | 68%         | 68%         |
| C2-LLM   | 75.0%           | 93.8%         | 99%      | 95%         | 95%         |
| C2-RND   | 65.4%           | 68.8%         | 86%      | 74%         | 88%         |
| C3-EVO   | 75.0%           | 90.6%         | 99%      | 68%         | 68%         |
| C3-LLM   | 75.0%           | 93.8%         | 99%      | 95%         | 95%         |
| C3-RND   | 65.4%           | 68.8%         | 86%      | 74%         | 88%         |
| C4-EVO   | 75.0%           | 90.6%         | 99%      | 68%         | 68%         |
| C4-LLM   | 75.0%           | 93.8%         | 99%      | 95%         | 95%         |
| C4-RND   | 65.4%           | 68.8%         | 86%      | 74%         | 88%         |

#### SonarCloud code smells (da branch dedicati su SonarCloud)

| Branch   | Files | Maint  | Smells | Con | Int | Ada | Res |
|----------|-------|--------|--------|-----|-----|-----|-----|
| C1-EVO   | 3     | A (10) | 10     | 2   | 4   | 4   | 0   |
| C1-LLM   | 1*    | A (0)  | 0*     | 0   | 0   | 0   | 0   |
| C1-RND   | 5     | A (284)| 284    | 4   | 276 | 4   | 0   |
| C2-EVO   | 3     | A (14) | 14     | 2   | 8   | 4   | 0   |
| C2-LLM   | 1*    | A (0)  | 0*     | 0   | 0   | 0   | 0   |
| C2-RND   | 5     | A (284)| 284    | 4   | 276 | 4   | 0   |
| C3-EVO   | 3     | A (11) | 11     | 2   | 4   | 5   | 0   |
| C3-LLM   | 1*    | A (0)  | 0*     | 0   | 0   | 0   | 0   |
| C3-RND   | 5     | A (284)| 284    | 4   | 276 | 4   | 0   |
| C4-EVO   | 3     | A (11) | 11     | 2   | 4   | 5   | 0   |
| C4-LLM   | 1*    | A (0)  | 0*     | 0   | 0   | 0   | 0   |
| C4-RND   | 5     | A (284)| 284    | 4   | 276 | 4   | 0   |

*PROBLEMA LLM: SonarCloud classifica automaticamente i file *Test.java come test e non li analizza per code smells. I file LLM non vengono contati. Il C0 del CSV aveva 60 smells (probabilmente analizzato in modo diverso in precedenza).

### Cosa resta da fare
1. **Risolvere il problema LLM code smells**: i test LLM non vengono rilevati da SonarCloud. Possibili soluzioni:
   - Rinominare temporaneamente i file test (rimuovere "Test" dal nome)
   - Accettare che LLM smells = solo PathTrie smells (0)
   - Chiedere all'utente come erano stati calcolati i C0
2. **Campo "Chiarezza"**: e' un valore soggettivo/manuale, non automatizzabile
3. **Aggiornare i CSV finali** con valori definitivi
4. **Ripristinare il pom.xml** allo stato originale

### Config tecnica
- SonarCloud token valido: `a7616df5341959e0cf6a7bbe151da94168e4d9d1`
- Project key: `lucacupellaro_zookeeper`
- Organization: `lucacupellaro`
- sonar-scanner CLI: `/tmp/sonar-scanner-5.0.1.3006-linux/bin/sonar-scanner`
- Per SonarCloud serve Java 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`
- Metodo branch separati: ogni combo Cx-SUITE ha un branch SonarCloud dedicato (es. C1-EVO, C2-LLM, ecc.)
- Il pom.xml di zookeeper-server ha `sonar.sources` e `sonar.tests` nelle properties (da rimuovere alla fine)

### Stato pom.xml attuale
- surefire include: `PathTrieTest/TestLLM/C2/*.java` (da aggiornare per la prossima run)
- PIT targetTests: `PathTrieTest.TestLLM.C2.*` (da aggiornare)
- Properties aggiunte: `sonar.sources` e `sonar.tests` (da rimuovere alla fine)
- PathTrie.java: attualmente e' la versione C4 (o C2, da verificare). Backup originale in `/tmp/PathTrie_original_backup.java`