      ******************************************************************
      * Program     : COSGN00C.CBL  (KICKS / OS-VS COBOL PORT)
      * Application : CardDemo
      * Type        : KICKS COBOL Program (command level)
      * Function    : Signon Screen for the CardDemo Application
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
      * This is a port of cobol/COSGN00C.cbl to the MVT ANS COBOL
      * compiler shipped with MVS 3.8j TK5 and the KICKS for TSO 1.5.0
      * command-level interface.  Every deviation from the original is
      * listed in docs/KICKS-NOTES.md.  The original is unchanged.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COSGN00C.
       AUTHOR.     AWS.
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-VARIABLES.
         05 WS-PGMNAME                 PIC X(08) VALUE 'COSGN00C'.
         05 WS-TRANID                  PIC X(04) VALUE 'CC00'.
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
         05 WS-USRSEC-FILE             PIC X(08) VALUE 'USRSEC  '.
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
         05 WS-RESP-CD                 PIC S9(04) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(04) COMP VALUE ZEROS.
         05 WS-USR-MODIFIED            PIC X(01) VALUE 'N'.
           88 USR-MODIFIED-YES                   VALUE 'Y'.
           88 USR-MODIFIED-NO                    VALUE 'N'.
         05 WS-USER-ID                 PIC X(08).
         05 WS-USER-PWD                PIC X(08).
         05 WS-SEC-LEN                 PIC S9(04) COMP VALUE 80.
         05 WS-KEY-LEN                 PIC S9(04) COMP VALUE 8.
         05 WS-SYSID4                  PIC X(04) VALUE SPACES.
         05 WS-ABSTIME                 PIC S9(15) COMP-3 VALUE ZERO.
         05 WS-DATE-MMDDYY             PIC X(08) VALUE SPACES.
         05 WS-TIME-HHMMSS             PIC X(08) VALUE SPACES.
         05 WS-COMMAREA-LEN            PIC S9(04) COMP VALUE ZERO.
         05 WS-CA-LEN                  PIC S9(04) COMP VALUE 160.
         05 WS-MSG-LEN                 PIC S9(04) COMP VALUE 80.

       COPY COCOM01Y.

       COPY COSGN00.

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
           MOVE SPACES TO ERRMSGO OF COSGN0AO.

           IF EIBCALEN = 0
               MOVE LOW-VALUES TO COSGN0AO
               MOVE -1       TO USERIDL OF COSGN0AI
               PERFORM SEND-SIGNON-SCREEN
           ELSE
               PERFORM PROCESS-AID-KEY.

           EXEC KICKS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
                     LENGTH (WS-CA-LEN)
           END-EXEC.

      *----------------------------------------------------------------*
      * EVALUATE EIBAID in the original; nested IF for ANS-74 COBOL.
      *----------------------------------------------------------------*
       PROCESS-AID-KEY.

           IF EIBAID = KIKENTER
               PERFORM PROCESS-ENTER-KEY
           ELSE
           IF EIBAID = KIKPF3
               MOVE CCDA-MSG-THANK-YOU        TO WS-MESSAGE
               PERFORM SEND-PLAIN-TEXT
           ELSE
               MOVE 'Y'                       TO WS-ERR-FLG
               MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
               PERFORM SEND-SIGNON-SCREEN.

      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      *----------------------------------------------------------------*
       PROCESS-ENTER-KEY.

           EXEC KICKS RECEIVE
                     MAP('COSGN0A')
                     MAPSET('COSGN00')
                     INTO(COSGN0AI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

           IF USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
               MOVE 'Y'      TO WS-ERR-FLG
               MOVE 'Please enter User ID ...' TO WS-MESSAGE
               MOVE -1       TO USERIDL OF COSGN0AI
               PERFORM SEND-SIGNON-SCREEN
           ELSE
           IF PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
               MOVE 'Y'      TO WS-ERR-FLG
               MOVE 'Please enter Password ...' TO WS-MESSAGE
               MOVE -1       TO PASSWDL OF COSGN0AI
               PERFORM SEND-SIGNON-SCREEN
           ELSE
               NEXT SENTENCE.

           IF ERR-FLG-OFF
               MOVE USERIDI OF COSGN0AI TO WS-USER-ID
               MOVE PASSWDI OF COSGN0AI TO WS-USER-PWD
               PERFORM UPPER-CASE-CREDENTIALS
               MOVE WS-USER-ID TO CDEMO-USER-ID
               PERFORM READ-USER-SEC-FILE.

      *----------------------------------------------------------------*
      * FUNCTION UPPER-CASE is COBOL-85; TRANSFORM is the OS/VS COBOL
      * extension that does the same job on this compiler.
      *----------------------------------------------------------------*
       UPPER-CASE-CREDENTIALS.

           TRANSFORM WS-USER-ID FROM 'abcdefghijklmnopqrstuvwxyz'
                                TO   'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.
           TRANSFORM WS-USER-PWD FROM 'abcdefghijklmnopqrstuvwxyz'
                                 TO   'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.

      *----------------------------------------------------------------*
      *                      SEND-SIGNON-SCREEN
      *----------------------------------------------------------------*
       SEND-SIGNON-SCREEN.

           PERFORM POPULATE-HEADER-INFO.

           MOVE WS-MESSAGE TO ERRMSGO OF COSGN0AO.

           EXEC KICKS SEND
                     MAP('COSGN0A')
                     MAPSET('COSGN00')
                     FROM(COSGN0AO)
                     ERASE
                     CURSOR
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-PLAIN-TEXT
      *----------------------------------------------------------------*
       SEND-PLAIN-TEXT.

           EXEC KICKS SEND TEXT
                     FROM(WS-MESSAGE)
                     LENGTH(WS-MSG-LEN)
                     ERASE
                     FREEKB
           END-EXEC.

           EXEC KICKS RETURN
           END-EXEC.

      *----------------------------------------------------------------*
      * FUNCTION CURRENT-DATE is COBOL-85; use ASKTIME / FORMATTIME.
      * ASSIGN APPLID does not exist in KICKS; ASSIGN SYSID is X(4).
      *----------------------------------------------------------------*
       POPULATE-HEADER-INFO.

           MOVE CCDA-TITLE01           TO TITLE01O OF COSGN0AO.
           MOVE CCDA-TITLE02           TO TITLE02O OF COSGN0AO.
           MOVE WS-TRANID              TO TRNNAMEO OF COSGN0AO.
           MOVE WS-PGMNAME             TO PGMNAMEO OF COSGN0AO.

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

           MOVE WS-DATE-MMDDYY         TO CURDATEO OF COSGN0AO.
           MOVE WS-TIME-HHMMSS         TO CURTIMEO OF COSGN0AO.

           MOVE 'KICKS   '             TO APPLIDO OF COSGN0AO.

           EXEC KICKS ASSIGN
                     SYSID(WS-SYSID4)
           END-EXEC.
           MOVE WS-SYSID4              TO SYSIDO OF COSGN0AO.

      *----------------------------------------------------------------*
      *                      READ-USER-SEC-FILE
      *----------------------------------------------------------------*
       READ-USER-SEC-FILE.

           EXEC KICKS READ
                DATASET   (WS-USRSEC-FILE)
                INTO      (SEC-USER-DATA)
                LENGTH    (WS-SEC-LEN)
                RIDFLD    (WS-USER-ID)
                KEYLENGTH (WS-KEY-LEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.

           IF WS-RESP-CD = KIKRESP(NORMAL)
               PERFORM CHECK-PASSWORD
           ELSE
           IF WS-RESP-CD = KIKRESP(NOTFND)
               MOVE 'Y'     TO WS-ERR-FLG
               MOVE 'User not found. Try again ...' TO WS-MESSAGE
               MOVE -1       TO USERIDL OF COSGN0AI
               PERFORM SEND-SIGNON-SCREEN
           ELSE
               MOVE 'Y'     TO WS-ERR-FLG
               MOVE 'Unable to verify the User ...' TO WS-MESSAGE
               MOVE -1       TO USERIDL OF COSGN0AI
               PERFORM SEND-SIGNON-SCREEN.

       CHECK-PASSWORD.

           IF SEC-USR-PWD = WS-USER-PWD
               MOVE WS-USER-ID   TO CDEMO-USER-ID
               MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
               MOVE ZEROS        TO CDEMO-PGM-CONTEXT
               PERFORM TRANSFER-TO-MENU
           ELSE
               MOVE 'Y'     TO WS-ERR-FLG
               MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
               MOVE -1       TO PASSWDL OF COSGN0AI
               PERFORM SEND-SIGNON-SCREEN.

      *----------------------------------------------------------------*
      * KICKS requires the XCTL COMMAREA to live outside the program's
      * WORKING-STORAGE, so it is copied into the incoming DFHCOMMAREA
      * (always present here: we only reach this on a RETURN TRANSID).
      *----------------------------------------------------------------*
       TRANSFER-TO-MENU.

           MOVE EIBCALEN TO WS-COMMAREA-LEN.
           MOVE CARDDEMO-COMMAREA TO DFHCOMMAREA.

           IF CDEMO-USRTYP-ADMIN
               EXEC KICKS XCTL
                   PROGRAM ('COADM01C')
                   COMMAREA(DFHCOMMAREA)
                   LENGTH(WS-COMMAREA-LEN)
                   RESP(WS-RESP-CD)
               END-EXEC
           ELSE
               EXEC KICKS XCTL
                   PROGRAM ('COMEN01C')
                   COMMAREA(DFHCOMMAREA)
                   LENGTH(WS-COMMAREA-LEN)
                   RESP(WS-RESP-CD)
               END-EXEC.

           MOVE 'Y'     TO WS-ERR-FLG.
           MOVE 'Signed on, but the menu program is not installed'
                        TO WS-MESSAGE.
           PERFORM SEND-SIGNON-SCREEN.
