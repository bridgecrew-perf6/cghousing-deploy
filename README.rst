================================================================================
  Deploy Common Ground (CG) Co-op Web Site
================================================================================

This project contains the cghousing web site (a Django application) and all of
its service dependencies (nginx, postgres) as Docker containers. It should
be useful for deploying the web site locally or on a container-based hosting
platform.


Install / Deploy
================================================================================

This assumes you have docker and make installed.

First, create the ``cg`` Docker network::

    $ make network

Build the PostgreSQL (postgres) image::

    $ make db-build

Run the postgres image::

    $ make db-run

Load the SQL dump file into the postgres image::

    $ make db-load

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


Production Deployment
================================================================================


TODOs
================================================================================

- SMTP server

  - https://github.com/tomav/docker-mailserver/blob/master/docker-compose.yml
  - https://www.digitalocean.com/community/tutorials/how-to-run-your-own-mail-server-with-mail-in-a-box-on-ubuntu-14-04
  - https://www.digitalocean.com/community/questions/how-to-set-up-email-on-digitalocean-droplet
