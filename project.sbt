sbtPlugin := false

name := "imce.third_party.jena_libraries"

moduleName := name.value

organization := "gov.nasa.jpl.imce"

organizationName := "JPL-IMCE"

homepage := Some(url(s"https://github.com/${organizationName.value}/${moduleName.value}"))

organizationHomepage := Some(url("http://www.jpl.nasa.gov"))

git.remoteRepo := s"git@github.com:${organizationName.value}/${moduleName.value}.git"

startYear := Some(2015)

scmInfo := Some(ScmInfo(
  browseUrl = url(s"https://github.com/${organizationName.value}/${moduleName.value}"),
  connection = "scm:"+git.remoteRepo.value))

developers := List(
  Developer(
    id="NicolasRouquette",
    name="Nicolas F. Rouquette",
    email="nicolas.f.rouquette@jpl.nasa.gov",
    url=url("https://github.com/NicolasRouquette")))

