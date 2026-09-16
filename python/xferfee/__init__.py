"""Python port of the CardDemo ``xferfee`` batch chain.

Specification: ``docs/specs/XFERFEE-BUSINESS-SPEC.md`` (business rules BR-1..BR-18).

Step modules mirror the JCL PROC ``XFERFEEP``:

* ``extract``   - STEP010 ``CBXFR01C``
* ``post_fees`` - STEP020 ``XFERFEE``
* ``reconcile`` - STEP030 ``CBXFR03C``
* ``run_chain`` - job ``XFRDAILY`` (step ordering, GDG allocation, COND, SYSOUT, RC)
"""
