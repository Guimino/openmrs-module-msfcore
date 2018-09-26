#!/bin/sh

if [ ! -z "$1" ]
then
rm docker-compose*
wget https://raw.githubusercontent.com/edrisse/openmrs-module-msfcore/master/backup/docker-compose/docker-compose.yml
wget https://raw.githubusercontent.com/edrisse/openmrs-module-msfcore/master/backup/docker-compose/docker-compose.override.yml
wget https://raw.githubusercontent.com/edrisse/openmrs-module-msfcore/master/backup/docker-compose/docker-compose.prod.yml
echo $1 > zip_file_password.txt
mkdir backup
else
  echo "Please specify a password for the zip file as command line argument"
fi
