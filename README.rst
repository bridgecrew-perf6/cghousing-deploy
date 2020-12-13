================================================================================
  Deploy Common Ground (CG) Co-op Web Site
================================================================================

This project contains the cghousing web site (a Django application) and all of
its service dependencies (nginx, postgres, smtp) as Docker containers. It should
be useful for deploying the web site locally or on a container-based hosting
platform.


Deploy the CG Housing App using Docker
================================================================================

Use ``make`` for all the things!

Create the ``cg`` Docker network::

    $ make network

Build the PostgreSQL (postgres) image::

    $ make pg-build

Run the postgres image::

    $ make pg-run

Load the SQL dump file into the postgres image::

    $ make pg-load

.. note:: I had to change the role in the dump file from ``postgres`` to
          ``cgprod``.

Build the Django image::

    $ make dj-build

Run the Django image::

    $ make dj-run

Build the Nginx image::

    $ make server-build

Add the following line to ``/etc/hosts`` file so that you can navigate to
http://cghousing.org:6080 to view the application::

    127.0.0.1 cghousing.org


TODOs
================================================================================

- User uploaded files are not being served.
