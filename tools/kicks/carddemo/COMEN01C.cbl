      ******************************************************************
      * Program     : COMEN01C.CBL  (KICKS / OS-VS COBOL PORT)
      * Application : CardDemo
      * Type        : KICKS COBOL Program (command level)
      * Function    : Main Menu for the CardDemo Application
      ******************************************************************
      * Copyright Amazon.com, Inc. or its affiliates.
      * All Rights Reserved.
      *
      * Licensed under the Apache License, Version 2.0 (the "License").
      * You may not use this file except in compliance with the License.
      * You may obtain a copy of the License at
      *
      *    http://www.apache.org/licenses/LICENSE-2.0
      *
      * Unless required by applicable law or agreed to in writing,
      * software distributed under the License is distributed on an
      * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
      * either express or implied. See the License for the specific
      * language governing permissions and limitations under the License
      ******************************************************************
      * This is a port of cobol/COMEN01C.cbl to the MVT ANS COBOL
      * compiler shipped with MVS 3.8j TK5 and the KICKS for TSO 1.5.0
      * command-level interface.  Every deviation from the original is
      * listed in docs/KICKS-NOTES.md.  The original is unchanged.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COMEN01C.
       AUTHOR.     AWS.
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-VARIABLES.
         05 WS-PGMNAME                 PIC X(08) VALUE 'COMEN01C'.
         05 WS-TRANID                  PIC X(04) VALUE 'CM00'.
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
         05 WS-USRSEC-FILE             PIC X(08) VALUE 'USRSEC  '.
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
         05 WS-RESP-CD                 PIC S9(04) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(04) COMP VALUE ZEROS.
         05 WS-OPTION-X                PIC X(02).
         05 WS-OPTION-XR REDEFINES WS-OPTION-X.
           10 WS-OPTION-X1             PIC X(01).
           10 WS-OPTION-X2             PIC X(01).
         05 WS-OPTION                  PIC 9(02) VALUE 0.
         05 WS-IDX                     PIC S9(04) COMP VALUE ZEROS.
         05 WS-MENU-OPT-TXT.
           10 WS-MO-NUM                PIC X(02).
           10 WS-MO-DOT                PIC X(02).
           10 WS-MO-NAME               PIC X(36).
         05 WS-OPT-MSG.
           10 WS-OM-HEAD               PIC X(12) VALUE 'This option '.
           10 WS-OM-NAME               PIC X(35).
           10 WS-OM-TAIL               PIC X(21).
         05 WS-ABSTIME                 PIC S9(15) COMP-3 VALUE ZERO.
         05 WS-DATE-MMDDYY             PIC X(08) VALUE SPACES.
         05 WS-TIME-HHMMSS             PIC X(08) VALUE SPACES.
         05 WS-COMMAREA-LEN            PIC S9(04) COMP VALUE ZERO.
         05 WS-CA-LEN                  PIC S9(04) COMP VALUE 160.

       COPY COCOM01Y.
       COPY COMEN02Y.

       COPY COMEN01.

       COPY COTTL01Y.
       COPY CSDAT01Y.
       COPY CSMSG01Y.
       COPY CSUSR01Y.

       COPY KIKAID.
       COPY KIKBMSCA.

       LINKAGE SECTION.
       01  DFHCOMMAREA.
         05  LK-COMMAREA               PIC X(01) OCCURS 1 TO 32767 TIMES
                                         DEPENDING ON EIBCALEN.

       PROCEDURE DIVISION.
       MAIN-PARA.

           MOVE 'N' TO WS-ERR-FLG.
           MOVE SPACES TO WS-MESSAGE.
           MOVE SPACES TO ERRMSGO OF COMEN1AO.

           IF EIBCALEN = 0
               MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
               PERFORM RETURN-TO-SIGNON-SCREEN
           ELSE
               MOVE DFHCOMMAREA TO CARDDEMO-COMMAREA
               PERFORM PROCESS-COMMAREA.

           EXEC KICKS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
                     LENGTH (WS-CA-LEN)
           END-EXEC.

       PROCESS-COMMAREA.

           IF NOT CDEMO-PGM-REENTER
               MOVE 1                   TO CDEMO-PGM-CONTEXT
               MOVE LOW-VALUES          TO COMEN1AO
               PERFORM SEND-MENU-SCREEN
           ELSE
               PERFORM RECEIVE-MENU-SCREEN
               PERFORM PROCESS-AID-KEY.

      *----------------------------------------------------------------*
      * EVALUATE EIBAID in the original; nested IF for ANS-74 COBOL.
      *----------------------------------------------------------------*
       PROCESS-AID-KEY.

           IF EIBAID = KIKENTER
               PERFORM PROCESS-ENTER-KEY
           ELSE
           IF EIBAID = KIKPF3
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
               PERFORM RETURN-TO-SIGNON-SCREEN
           ELSE
               MOVE 'Y'                       TO WS-ERR-FLG
               MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
               PERFORM SEND-MENU-SCREEN.

      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      * The original right-justifies the typed option with reference
      * modification (OPTIONI(1:WS-IDX)); the field is 2 bytes so a
      * REDEFINES does the same job here.  This compiler has no STRING
      * or INSPECT verbs at all, so messages are assembled by MOVEing
      * into a group of fixed-width fields.
      *----------------------------------------------------------------*
       PROCESS-ENTER-KEY.

           MOVE OPTIONI OF COMEN1AI      TO WS-OPTION-X.
           IF WS-OPTION-X2 = SPACE OR WS-OPTION-X2 = LOW-VALUE
               MOVE WS-OPTION-X1         TO WS-OPTION-X2
               MOVE '0'                  TO WS-OPTION-X1.
           IF WS-OPTION-X1 = SPACE OR WS-OPTION-X1 = LOW-VALUE
               MOVE '0'                  TO WS-OPTION-X1.
           IF WS-OPTION-X2 = SPACE OR WS-OPTION-X2 = LOW-VALUE
               MOVE '0'                  TO WS-OPTION-X2.
           MOVE WS-OPTION-X              TO WS-OPTION.
           MOVE WS-OPTION                TO OPTIONO OF COMEN1AO.

           IF WS-OPTION IS NOT NUMERIC OR
              WS-OPTION > CDEMO-MENU-OPT-COUNT OR
              WS-OPTION = ZEROS
               MOVE 'Y'     TO WS-ERR-FLG
               MOVE 'Please enter a valid option number...' TO
                               WS-MESSAGE
               PERFORM SEND-MENU-SCREEN.

           IF ERR-FLG-OFF AND CDEMO-USRTYP-USER AND
              CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
               MOVE 'Y'                TO WS-ERR-FLG
               MOVE 'No access - Admin Only option... ' TO
                                       WS-MESSAGE
               PERFORM SEND-MENU-SCREEN.

           IF ERR-FLG-OFF
               PERFORM TRANSFER-TO-OPTION.

      *----------------------------------------------------------------*
      * EXEC CICS INQUIRE PROGRAM does not exist in KICKS.  We just try
      * the XCTL with RESP and report PGMIDERR as "not installed".
      * KICKS also requires the XCTL COMMAREA to live outside
      * WORKING-STORAGE, so it goes through DFHCOMMAREA.
      *----------------------------------------------------------------*
       TRANSFER-TO-OPTION.

           IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION) = 'DUMMY   '
               MOVE KIKGREEN           TO ERRMSGC OF COMEN1AI
               MOVE CDEMO-MENU-OPT-NAME(WS-OPTION) TO WS-OM-NAME
               MOVE ' is coming soon ...' TO WS-OM-TAIL
               MOVE WS-OPT-MSG         TO WS-MESSAGE
               PERFORM SEND-MENU-SCREEN
           ELSE
               MOVE WS-TRANID    TO CDEMO-FROM-TRANID
               MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
               MOVE ZEROS        TO CDEMO-PGM-CONTEXT
               MOVE EIBCALEN     TO WS-COMMAREA-LEN
               MOVE CARDDEMO-COMMAREA TO DFHCOMMAREA
               EXEC KICKS XCTL
                   PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
                   COMMAREA(DFHCOMMAREA)
                   LENGTH(WS-COMMAREA-LEN)
                   RESP(WS-RESP-CD)
               END-EXEC
               MOVE 1            TO CDEMO-PGM-CONTEXT
               MOVE KIKRED             TO ERRMSGC OF COMEN1AI
               MOVE CDEMO-MENU-OPT-NAME(WS-OPTION) TO WS-OM-NAME
               MOVE ' is not installed...' TO WS-OM-TAIL
               MOVE WS-OPT-MSG         TO WS-MESSAGE
               PERFORM SEND-MENU-SCREEN.

      *----------------------------------------------------------------*
      *                      RETURN-TO-SIGNON-SCREEN
      *----------------------------------------------------------------*
       RETURN-TO-SIGNON-SCREEN.

           IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
           EXEC KICKS
               XCTL PROGRAM(CDEMO-TO-PROGRAM)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-MENU-SCREEN
      *----------------------------------------------------------------*
       SEND-MENU-SCREEN.

           PERFORM POPULATE-HEADER-INFO.
           PERFORM BUILD-MENU-OPTIONS.

           MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO.

           EXEC KICKS SEND
                     MAP('COMEN1A')
                     MAPSET('COMEN01')
                     FROM(COMEN1AO)
                     ERASE
           END-EXEC.

      *----------------------------------------------------------------*
      *                      RECEIVE-MENU-SCREEN
      *----------------------------------------------------------------*
       RECEIVE-MENU-SCREEN.

           EXEC KICKS RECEIVE
                     MAP('COMEN1A')
                     MAPSET('COMEN01')
                     INTO(COMEN1AI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      * FUNCTION CURRENT-DATE is COBOL-85; use ASKTIME / FORMATTIME.
      *----------------------------------------------------------------*
       POPULATE-HEADER-INFO.

           MOVE CCDA-TITLE01           TO TITLE01O OF COMEN1AO.
           MOVE CCDA-TITLE02           TO TITLE02O OF COMEN1AO.
           MOVE WS-TRANID              TO TRNNAMEO OF COMEN1AO.
           MOVE WS-PGMNAME             TO PGMNAMEO OF COMEN1AO.

           EXEC KICKS ASKTIME
                     ABSTIME(WS-ABSTIME)
           END-EXEC.
           EXEC KICKS FORMATTIME
                     ABSTIME(WS-ABSTIME)
                     MMDDYY(WS-DATE-MMDDYY)
                     DATESEP('/')
           END-EXEC.
           EXEC KICKS FORMATTIME
                     ABSTIME(WS-ABSTIME)
                     TIME(WS-TIME-HHMMSS)
                     TIMESEP(':')
           END-EXEC.

           MOVE WS-DATE-MMDDYY         TO CURDATEO OF COMEN1AO.
           MOVE WS-TIME-HHMMSS         TO CURTIMEO OF COMEN1AO.

      *----------------------------------------------------------------*
      * Inline PERFORM VARYING ... END-PERFORM and EVALUATE WS-IDX in
      * the original; out-of-line PERFORM and nested IF here.
      *----------------------------------------------------------------*
       BUILD-MENU-OPTIONS.

           PERFORM BUILD-ONE-OPTION
                   VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT.

       BUILD-ONE-OPTION.

           MOVE SPACES             TO WS-MENU-OPT-TXT.

           MOVE CDEMO-MENU-OPT-NUM(WS-IDX)  TO WS-MO-NUM.
           MOVE '. '                        TO WS-MO-DOT.
           MOVE CDEMO-MENU-OPT-NAME(WS-IDX) TO WS-MO-NAME.

           IF WS-IDX = 1
               MOVE WS-MENU-OPT-TXT TO OPTN001O
           ELSE IF WS-IDX = 2
               MOVE WS-MENU-OPT-TXT TO OPTN002O
           ELSE IF WS-IDX = 3
               MOVE WS-MENU-OPT-TXT TO OPTN003O
           ELSE IF WS-IDX = 4
               MOVE WS-MENU-OPT-TXT TO OPTN004O
           ELSE IF WS-IDX = 5
               MOVE WS-MENU-OPT-TXT TO OPTN005O
           ELSE IF WS-IDX = 6
               MOVE WS-MENU-OPT-TXT TO OPTN006O
           ELSE IF WS-IDX = 7
               MOVE WS-MENU-OPT-TXT TO OPTN007O
           ELSE IF WS-IDX = 8
               MOVE WS-MENU-OPT-TXT TO OPTN008O
           ELSE IF WS-IDX = 9
               MOVE WS-MENU-OPT-TXT TO OPTN009O
           ELSE IF WS-IDX = 10
               MOVE WS-MENU-OPT-TXT TO OPTN010O
           ELSE IF WS-IDX = 11
               MOVE WS-MENU-OPT-TXT TO OPTN011O
           ELSE IF WS-IDX = 12
               MOVE WS-MENU-OPT-TXT TO OPTN012O.
