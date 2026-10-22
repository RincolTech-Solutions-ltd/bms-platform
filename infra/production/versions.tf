terraform {
  required_version = ">= 1.6"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.49"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 4.52"
    }
  }
}

# Credentials come from the environment, never from a file in this repository:
#   HCLOUD_TOKEN           a read/write token of the Rincol Tech Hetzner Cloud project
#   CLOUDFLARE_API_TOKEN   a token with Zone.DNS:Edit on the rincoltech.com zone only
provider "hcloud" {}
provider "cloudflare" {}
