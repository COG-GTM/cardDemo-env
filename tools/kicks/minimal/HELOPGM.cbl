       IDENTIFICATION DIVISION.
       PROGRAM-ID.  HELOPGM.
      *****************************************************************
      * CardDemo-env KICKS probe transaction HELO.                    *
      * Pseudo-conversational: first entry paints HELOMAP, ENTER      *
      * reads the NAME field and echoes a greeting, PF3 ends.         *
      * Written for the OS/VS (MVT ANSI) COBOL compiler shipped with  *
      * MVS 3.8j: no scope terminators, no EVALUATE, no inline        *
      * PERFORM, no STRING with pointers.                              *
      *****************************************************************
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-COMMAREA                 PIC X.
       01  WS-FLAGS.
           05  WS-SEND-FLAG            PIC X.
               88  SEND-ERASE                   VALUE '1'.
               88  SEND-DATAONLY                VALUE '2'.
       01  WS-NAME-LEN                 PIC S9(4) COMP VALUE 0.
       01  WS-BYE-MSG                  PIC X(40)
               VALUE 'HELO ENDED - TYPE KSSF TO STOP KICKS'.
       01  WS-BYE-LEN                  PIC S9(4) COMP VALUE 40.
       01  WS-TIME-TEXT.
           05  FILLER                  PIC X(11) VALUE 'KICKS TIME '.
           05  WS-HH                   PIC 99.
           05  FILLER                  PIC X VALUE ':'.
           05  WS-MM                   PIC 99.
           05  FILLER                  PIC X VALUE ':'.
           05  WS-SS                   PIC 99.
           05  FILLER                  PIC X(11) VALUE '  ON MVS38J'.
       01  WS-EIBTIME-X.
           05  WS-EIBTIME-N            PIC 9(7).
       01  WS-EIBTIME-R REDEFINES WS-EIBTIME-X.
           05  FILLER                  PIC 9.
           05  WS-T-HH                 PIC 99.
           05  WS-T-MM                 PIC 99.
           05  WS-T-SS                 PIC 99.
       01  WS-GREETING.
           05  FILLER                  PIC X(7) VALUE 'HELLO, '.
           05  WS-GREET-NAME           PIC X(20).
           05  FILLER                  PIC X(34)
               VALUE ' - SENT BY A REAL BMS MAP ON KICKS'.
       COPY HELOSET.
       COPY KIKAID.
       LINKAGE SECTION.
       01  KIKCOMMAREA                 PIC X.
       PROCEDURE DIVISION.
       0000-MAIN.
           IF EIBCALEN = ZERO
               PERFORM 1000-FIRST-TIME
           ELSE
           IF EIBAID = KIKPF3
               PERFORM 3000-GOODBYE
           ELSE
           IF EIBAID = KIKCLEAR
               PERFORM 1000-FIRST-TIME
           ELSE
               PERFORM 2000-GREET.
           EXEC KICKS
               RETURN TRANSID('HELO')
                      COMMAREA(WS-COMMAREA)
                      LENGTH(1)
           END-EXEC.
       1000-FIRST-TIME.
           MOVE LOW-VALUES TO HELOMAPO.
           PERFORM 1500-FORMAT-TIME.
           MOVE WS-TIME-TEXT TO CURTIMEO.
           MOVE 'Type your name and press ENTER.' TO MESSAGEO.
           MOVE '1' TO WS-SEND-FLAG.
           PERFORM 4000-SEND-MAP.
       1500-FORMAT-TIME.
           MOVE EIBTIME TO WS-EIBTIME-N.
           MOVE WS-T-HH TO WS-HH.
           MOVE WS-T-MM TO WS-MM.
           MOVE WS-T-SS TO WS-SS.
       2000-GREET.
           EXEC KICKS
               RECEIVE MAP('HELOMAP')
                       MAPSET('HELOSET')
                       INTO(HELOMAPI)
           END-EXEC.
      *    HELOMAPO redefines HELOMAPI - copy input before clearing it
           MOVE NAMEL TO WS-NAME-LEN.
           MOVE NAMEI TO WS-GREET-NAME.
           MOVE LOW-VALUES TO HELOMAPO.
           PERFORM 1500-FORMAT-TIME.
           MOVE WS-TIME-TEXT TO CURTIMEO.
           IF WS-NAME-LEN = ZERO OR WS-GREET-NAME = SPACES
               MOVE 'Please type a name first.' TO MESSAGEO
           ELSE
               MOVE WS-GREETING TO GREETINGO
               MOVE 'Greeting sent. ENTER again or F3 to exit.'
                   TO MESSAGEO.
           MOVE '2' TO WS-SEND-FLAG.
           PERFORM 4000-SEND-MAP.
       3000-GOODBYE.
           EXEC KICKS
               SEND TEXT FROM(WS-BYE-MSG)
                         LENGTH(WS-BYE-LEN)
                         ERASE
                         FREEKB
           END-EXEC.
           EXEC KICKS
               RETURN
           END-EXEC.
       4000-SEND-MAP.
           IF SEND-ERASE
               EXEC KICKS
                   SEND MAP('HELOMAP')
                        MAPSET('HELOSET')
                        FROM(HELOMAPO)
                        ERASE
               END-EXEC
           ELSE
               EXEC KICKS
                   SEND MAP('HELOMAP')
                        MAPSET('HELOSET')
                        FROM(HELOMAPO)
                        DATAONLY
               END-EXEC.
