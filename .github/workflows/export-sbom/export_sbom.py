import requests
import json

owner = 'vijay-art'
repo = 'Testing-SAST'
token = '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'

def export_dependency_graph():
    url = f'https://api.github.com/repos/{owner}/{repo}/dependency-graph/sbom'
    headers = {
        'Authorization': f'Bearer {token}',
        'Accept': 'application/vnd.github.hawkgirl-preview+json'  # Required media type for the Dependency Graph API
    }

    try:
        response = requests.get(url, headers=headers)
        response.raise_for_status()

        dependency_graph = response.json()
        sbom = dependency_graph['sbom']

        with open('sbom.json', 'w') as file:
            json.dump(sbom, file, indent=4)

        print('SBOM exported successfully.')
    except requests.exceptions.RequestException as e:
        print(f'Error exporting SBOM: {e}')

export_dependency_graph()
